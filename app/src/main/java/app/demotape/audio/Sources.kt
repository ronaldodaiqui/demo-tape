package app.demotape.audio

import app.demotape.data.Song
import app.demotape.data.SongRepository
import app.demotape.data.TRACKS
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

const val SR = 48000
const val BLOCK = 960
const val PEAK_HOP = 480

/** Something the engine can play. Writes [n] stereo frames for song frames [pos, pos+n). */
interface Source {
    fun render(pos: Long, l: FloatArray, r: FloatArray, n: Int)
}

/** Memory-maps take files so playback never holds whole songs on the heap. */
class ClipAudio(private val repo: SongRepository) {
    private val cache = ConcurrentHashMap<String, ShortBuffer>()

    fun get(songId: String, clipId: String): ShortBuffer? {
        cache[clipId]?.let { return it }
        val f = repo.clipFile(songId, clipId)
        if (!f.exists() || f.length() < 2) return null
        return try {
            val mapped = RandomAccessFile(f, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length() and 1L.inv())
            }
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            val sb = mapped.asShortBuffer()
            cache[clipId] = sb
            sb
        } catch (e: Exception) {
            null
        }
    }

    fun evict(clipId: String) {
        cache.remove(clipId)
    }

    fun clear() = cache.clear()
}

/** Fader position (0..1) to gain. 0.8 is unity; the top of the fader is +12 dB. */
fun faderGain(v: Float): Float =
    if (v >= 0.8f) 10f.pow((v - 0.8f) / 0.2f * 12f / 20f)
    else (v / 0.8f).let { it * it }

private const val FADE = 240 // 5 ms crossfade at take edges

private fun edgeGain(cf: Int, len: Long): Float {
    val fromEnd = (len - 1 - cf).toFloat()
    return min(1f, min(cf / FADE.toFloat(), fromEnd / FADE)).coerceAtLeast(0f)
}

/** Mixes the four tracks. Newer takes punch over older ones on the same track. */
class SongSource(@Volatile var song: Song, private val audio: ClipAudio) : Source {
    /** A track that is being recorded over and should not be heard. */
    @Volatile
    var silentTrack: Int = -1

    /** Plays the song's metronome click (when it's switched on). Off for exports. */
    @Volatile
    var metronome: Boolean = false

    /** The click also plays for song frames before this, even when switched off (count-in). */
    @Volatile
    var countInUntil: Long = Long.MIN_VALUE

    private var tb = FloatArray(BLOCK)
    private val eqs = Array(TRACKS) { ChannelEq() }
    private val drums = Drums.Player()

    // Gains in use at the end of the last block. New values ramp from these across the next
    // block, sample by sample, so moving a fader, pan, trim or mute never makes a step (crackle).
    private val curL = FloatArray(TRACKS) { Float.NaN }
    private val curR = FloatArray(TRACKS) { Float.NaN }
    private val clipGain = HashMap<String, Float>()

    override fun render(pos: Long, l: FloatArray, r: FloatArray, n: Int) {
        java.util.Arrays.fill(l, 0, n, 0f)
        java.util.Arrays.fill(r, 0, n, 0f)
        if (tb.size < n) tb = FloatArray(n)
        val s = song
        val silent = silentTrack
        val inv = 1f / n
        for (t in 0 until TRACKS) {
            val ts = s.tracks[t]
            val off = !s.audible(t) || t == silent
            val gain = if (off) 0f else faderGain(ts.volume) * 10f.pow(ts.trim / 20f)
            val (gl, gr) = panGains(ts.pan)
            val tL = gain * gl
            val tR = gain * gr
            if (curL[t].isNaN()) { curL[t] = tL; curR[t] = tR }
            val sL = curL[t]
            val sR = curR[t]
            curL[t] = tL
            curR[t] = tR
            if (tL == 0f && tR == 0f && sL == 0f && sR == 0f) {
                eqs[t].reset()
                continue
            }
            var any = false
            java.util.Arrays.fill(tb, 0, n, 0f)
            for (c in s.clips) {
                if (c.track != t || c.hidden) continue
                val cs = c.pos
                val a = maxOf(pos, cs)
                val b = minOf(pos + n, c.end)
                if (a >= b) continue
                val buf = audio.get(s.id, c.fileId) ?: continue
                val cap = buf.capacity()
                val skip = c.skip.toInt()
                val cgT = c.gainLin
                val cg0 = clipGain[c.id] ?: cgT
                clipGain[c.id] = cgT
                any = true
                var f = a
                while (f < b) {
                    val cf = (f - cs).toInt()
                    val si = cf + skip
                    val i = (f - pos).toInt()
                    val cg = cg0 + (cgT - cg0) * (i * inv)
                    val smp = if (si < cap) buf.get(si) * cg / 32768f else 0f
                    val g = edgeGain(cf, c.length)
                    tb[i] = tb[i] + (smp - tb[i]) * g
                    f++
                }
            }
            if (!any) {
                eqs[t].reset()
                continue
            }
            eqs[t].process(tb, n, ts.low, ts.mid, ts.high, ts.midFreq)
            val dL = (tL - sL) * inv
            val dR = (tR - sR) * inv
            for (i in 0 until n) {
                l[i] += tb[i] * (sL + dL * i)
                r[i] += tb[i] * (sR + dR * i)
            }
        }
        drums.render(s, pos, l, r, n)
        if (metronome) Metronome.render(s, pos, l, r, n, countInUntil)
    }
}

/** Click track on a grid that starts at song frame 0. Beat 1 of each bar is accented. */
object Metronome {
    private val len = SR * 40 / 1000
    private fun tone(freq: Double, amp: Double) = FloatArray(len) { i ->
        val t = i / SR.toDouble()
        val env = (1.0 - kotlin.math.exp(-t / 0.0004)) * kotlin.math.exp(-t / 0.010)
        (sin(2.0 * Math.PI * freq * t) * env * amp).toFloat()
    }

    private val accent = tone(2093.0, 0.9)
    private val beat = tone(1568.0, 0.6)

    fun beatLength(bpm: Int): Double = SR * 60.0 / bpm

    fun render(s: Song, pos: Long, l: FloatArray, r: FloatArray, n: Int, countInUntil: Long) {
        val on = s.click
        if (!on && pos >= countInUntil) return
        val bl = beatLength(s.bpm)
        // Up to about 3x, saturated so loud settings get punchier instead of clipping the mix.
        val gain = s.clickVolume * 3f
        val first = kotlin.math.floor((pos - len) / bl).toLong()
        val last = kotlin.math.floor((pos + n) / bl).toLong()
        var k = first
        while (k <= last) {
            val bf = kotlin.math.round(k * bl).toLong()
            if (on || bf < countInUntil) {
                val snd = if (Math.floorMod(k, s.beatsPerBar.toLong()) == 0L) accent else beat
                val a = maxOf(pos, bf)
                val b = minOf(pos + n, bf + len)
                var f = a
                while (f < b) {
                    val v = tanh(snd[(f - bf).toInt()] * gain)
                    val i = (f - pos).toInt()
                    l[i] += v
                    r[i] += v
                    f++
                }
            }
            k++
        }
    }
}

/** Short clicks for the sync check. */
class ClickSource(private val clicks: LongArray) : Source {
    private val len = SR * 30 / 1000
    private val click = FloatArray(len) { i ->
        val t = i / SR.toFloat()
        (sin(2.0 * Math.PI * 1760.0 * t) * exp(-t / 0.007) * 0.9).toFloat()
    }

    override fun render(pos: Long, l: FloatArray, r: FloatArray, n: Int) {
        java.util.Arrays.fill(l, 0, n, 0f)
        for (c in clicks) {
            val a = maxOf(pos, c)
            val b = minOf(pos + n, c + len)
            var f = a
            while (f < b) {
                l[(f - pos).toInt()] += click[(f - c).toInt()]
                f++
            }
        }
        System.arraycopy(l, 0, r, 0, n)
    }
}

fun softClip(x: Float): Float = if (x > 0.9f || x < -0.9f) tanh(x) else x

/**
 * Waveform overview: for every 10 ms hop, the highest and lowest sample, stored as two signed
 * bytes (max, min). Keeping both sides lets the timeline draw the true, slightly lopsided shape.
 */
class PeakBuilder {
    private var data = ByteArray(8192)
    private var count = 0
    private var hi = Int.MIN_VALUE
    private var lo = Int.MAX_VALUE
    private var accN = 0

    @Synchronized
    fun add(b: ShortArray, n: Int) {
        for (i in 0 until n) {
            val v = b[i].toInt()
            if (v > hi) hi = v
            if (v < lo) lo = v
            if (++accN == PEAK_HOP) push()
        }
    }

    private fun push() {
        if (count + 2 > data.size) data = data.copyOf(data.size * 2)
        data[count++] = toByte(hi)
        data[count++] = toByte(lo)
        hi = Int.MIN_VALUE
        lo = Int.MAX_VALUE
        accN = 0
    }

    @Synchronized
    fun flush() {
        if (accN > 0) push()
    }

    @Synchronized
    fun snapshot(): ByteArray = data.copyOf(count)

    companion object {
        fun toByte(v: Int): Byte = (v * 127 / 32767).coerceIn(-127, 127).toByte()

        /** Builds the overview for a whole take file (used for takes recorded before this format). */
        fun fromSamples(buf: java.nio.ShortBuffer): ByteArray {
            val pb = PeakBuilder()
            val chunk = ShortArray(PEAK_HOP * 16)
            var i = 0
            val n = buf.capacity()
            while (i < n) {
                val k = min(chunk.size, n - i)
                for (j in 0 until k) chunk[j] = buf.get(i + j)
                pb.add(chunk, k)
                i += k
            }
            pb.flush()
            return pb.snapshot()
        }
    }
}

/** Where recorded audio goes. The input thread sets [startFrame] on the first write. */
abstract class RecordSink {
    @Volatile
    var startFrame = -1L

    @Volatile
    var frames = 0L
    abstract fun write(b: ShortArray, n: Int)
    open fun finish() {}
}

class FileSink(file: File) : RecordSink() {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val bytes = ByteArray(BLOCK * 4)
    private var done = false
    val peaks = PeakBuilder()

    override fun write(b: ShortArray, n: Int) {
        if (done) return
        for (i in 0 until n) {
            val v = b[i].toInt()
            bytes[2 * i] = (v and 0xff).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        out.write(bytes, 0, n * 2)
        frames += n
        peaks.add(b, n)
    }

    override fun finish() {
        if (done) return
        done = true
        peaks.flush()
        out.flush()
        out.close()
    }
}

class MemorySink(capacity: Int) : RecordSink() {
    val data = ShortArray(capacity)

    override fun write(b: ShortArray, n: Int) {
        val room = data.size - frames.toInt()
        val k = min(n, room)
        if (k > 0) System.arraycopy(b, 0, data, frames.toInt(), k)
        frames += k
    }
}
