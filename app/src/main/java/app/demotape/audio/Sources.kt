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

/** Something the engine can play. Writes [n] mono samples for song frames [pos, pos+n). */
interface Source {
    fun render(pos: Long, out: FloatArray, n: Int)
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

    private var tb = FloatArray(BLOCK)

    override fun render(pos: Long, out: FloatArray, n: Int) {
        java.util.Arrays.fill(out, 0, n, 0f)
        if (tb.size < n) tb = FloatArray(n)
        val s = song
        val silent = silentTrack
        for (t in 0 until TRACKS) {
            val ts = s.tracks[t]
            if (ts.muted || t == silent) continue
            val gain = faderGain(ts.volume)
            if (gain <= 0f) continue
            var any = false
            java.util.Arrays.fill(tb, 0, n, 0f)
            for (c in s.clips) {
                if (c.track != t) continue
                val cs = c.pos
                val a = maxOf(pos, cs)
                val b = minOf(pos + n, c.end)
                if (a >= b) continue
                val buf = audio.get(s.id, c.id) ?: continue
                val cap = buf.capacity()
                any = true
                var f = a
                while (f < b) {
                    val cf = (f - cs).toInt()
                    val smp = if (cf < cap) buf.get(cf) / 32768f else 0f
                    val g = edgeGain(cf, c.length)
                    val i = (f - pos).toInt()
                    tb[i] = tb[i] + (smp - tb[i]) * g
                    f++
                }
            }
            if (any) for (i in 0 until n) out[i] += tb[i] * gain
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

    override fun render(pos: Long, out: FloatArray, n: Int) {
        java.util.Arrays.fill(out, 0, n, 0f)
        for (c in clicks) {
            val a = maxOf(pos, c)
            val b = minOf(pos + n, c + len)
            var f = a
            while (f < b) {
                out[(f - pos).toInt()] += click[(f - c).toInt()]
                f++
            }
        }
    }
}

fun softClip(x: Float): Float = if (x > 0.9f || x < -0.9f) tanh(x) else x

/** Running per-hop peak, kept for drawing waveforms (one byte per 10 ms). */
class PeakBuilder {
    private var data = ByteArray(4096)
    private var count = 0
    private var acc = 0
    private var accN = 0

    @Synchronized
    fun add(b: ShortArray, n: Int) {
        for (i in 0 until n) {
            val a = abs(b[i].toInt())
            if (a > acc) acc = a
            if (++accN == PEAK_HOP) push()
        }
    }

    private fun push() {
        if (count == data.size) data = data.copyOf(count * 2)
        data[count++] = min(255, acc * 255 / 32767).toByte()
        acc = 0
        accN = 0
    }

    @Synchronized
    fun flush() {
        if (accN > 0) push()
    }

    @Synchronized
    fun snapshot(): ByteArray = data.copyOf(count)
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
