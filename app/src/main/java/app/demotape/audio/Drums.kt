package app.demotape.audio

import app.demotape.data.Song
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh

/**
 * A small step-sequencer drum machine. Six synthesized voices (made once at startup, no
 * sample files), 16th-note steps on the metronome's grid, one or two bars, with swing.
 */
object Drums {
    const val VOICES = 6
    const val MAX_STEPS = 64
    val NAMES = listOf("Kick", "Snare", "Hat", "Open", "Clap", "Rim")
    private const val HAT = 2
    private const val OPEN = 3

    private val rng = java.util.Random(808)
    private fun noise() = rng.nextDouble() * 2 - 1

    private fun normalize(x: DoubleArray, level: Double): FloatArray {
        var pk = 1e-9
        for (v in x) pk = max(pk, abs(v))
        return FloatArray(x.size) { (x[it] / pk * level).toFloat() }
    }

    private fun len(sec: Double) = (SR * sec).toInt()

    private val kick: FloatArray = run {
        val n = len(0.45)
        var ph = 0.0
        val x = DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            val f = 48 + 110 * exp(-t / 0.03)
            ph += 2 * PI * f / SR
            val body = sin(ph) * exp(-t / 0.2)
            val click = noise() * exp(-t / 0.0015) * 0.25
            tanh(1.6 * (body + click))
        }
        normalize(x, 0.95)
    }

    private val snare: FloatArray = run {
        val n = len(0.3)
        val hp = Biquad().apply { highpass(1500.0, 0.7) }
        val x = DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            val tone = (sin(2 * PI * 185 * t) * 0.6 + sin(2 * PI * 330 * t) * 0.3) * exp(-t / 0.045)
            val nz = hp.process(noise()) * exp(-t / 0.11)
            0.55 * tone + 0.75 * nz
        }
        normalize(x, 0.8)
    }

    // 808-style metal: six detuned square waves, high-passed.
    private fun metal(decay: Double, sec: Double, level: Double): FloatArray {
        val n = len(sec)
        val freqs = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0).map { it * 1.6 }
        val hp1 = Biquad().apply { highpass(7000.0, 0.7) }
        val hp2 = Biquad().apply { highpass(7000.0, 0.7) }
        val x = DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            var sq = 0.0
            for (f in freqs) sq += if (sin(2 * PI * f * t) >= 0) 1.0 else -1.0
            val v = hp2.process(hp1.process(sq / 6 + noise() * 0.3))
            v * exp(-t / decay)
        }
        return normalize(x, level)
    }

    private val hat = metal(0.035, 0.15, 0.45)
    private val open = metal(0.22, 0.6, 0.4)

    private val clap: FloatArray = run {
        val n = len(0.35)
        val bp = Biquad().apply { bandpass(1200.0, 1.4) }
        val x = DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            var env = 0.0
            for (b in doubleArrayOf(0.0, 0.011, 0.022)) if (t >= b) env += exp(-(t - b) / 0.004)
            if (t >= 0.03) env += exp(-(t - 0.03) / 0.11) * 0.8
            bp.process(noise()) * env
        }
        normalize(x, 0.7)
    }

    private val rim: FloatArray = run {
        val n = len(0.1)
        val hp = Biquad().apply { highpass(3000.0, 0.7) }
        val x = DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            sin(2 * PI * 1700 * t) * exp(-t / 0.012) * 0.7 +
                sin(2 * PI * 820 * t) * exp(-t / 0.02) * 0.5 +
                hp.process(noise()) * exp(-t / 0.004) * 0.4
        }
        normalize(x, 0.6)
    }

    private val sounds = arrayOf(kick, snare, hat, open, clap, rim)
    private val longest = sounds.maxOf { it.size }

    fun stepLength(s: Song): Double = Metronome.beatLength(s.bpm) / 4
    fun stepsPerBar(s: Song): Int = s.beatsPerBar * 4
    fun steps(s: Song): Int = stepsPerBar(s) * s.drumBars

    /** Swing pushes the off-beat 8ths (and the 16ths between) late, up to a full triplet feel. */
    private fun swingOffset(idx: Int, s: Song, stepLen: Double): Double = when (idx % 4) {
        2 -> s.drumSwing * stepLen * 2 / 3
        1, 3 -> s.drumSwing * stepLen / 3
        else -> 0.0
    }

    private fun hit(s: Song, voice: Int, idx: Int): Boolean {
        val row = s.drumRows.getOrNull(voice) ?: return false
        return idx < row.length && row[idx] == 'x'
    }

    /** Song frame where step k (counted from song frame 0) sounds. */
    fun stepFrame(s: Song, k: Long, stepLen: Double): Long {
        val idx = Math.floorMod(k, steps(s).toLong()).toInt()
        return round(k * stepLen + swingOffset(idx, s, stepLen)).toLong()
    }

    fun render(s: Song, pos: Long, l: FloatArray, r: FloatArray, n: Int) {
        if (!s.drumsOn) return
        val gain = faderGain(s.drumVolume)
        if (gain <= 0f) return
        val stepLen = stepLength(s)
        val total = steps(s)
        if (total <= 0) return
        val first = floor((pos - longest) / stepLen).toLong() - 1
        val last = floor((pos + n) / stepLen).toLong()
        var k = first
        while (k <= last) {
            val idx = Math.floorMod(k, total.toLong()).toInt()
            val at = stepFrame(s, k, stepLen)
            for (v in 0 until VOICES) {
                if (!hit(s, v, idx)) continue
                val snd = sounds[v]
                var size = snd.size.toLong()
                if (v == OPEN) {
                    // A hat on a later step chokes the open hat, like on a real kit.
                    var j = k + 1
                    while (j <= k + total) {
                        val jdx = Math.floorMod(j, total.toLong()).toInt()
                        if (hit(s, HAT, jdx) || hit(s, OPEN, jdx)) {
                            size = min(size, stepFrame(s, j, stepLen) - at)
                            break
                        }
                        j++
                    }
                }
                val a = max(pos, at)
                val b = min(pos + n, at + size)
                var f = a
                val fadeFrom = at + size - 96
                while (f < b) {
                    var x = snd[(f - at).toInt()] * gain
                    if (f > fadeFrom && size < snd.size) x *= (at + size - f) / 96f
                    val i = (f - pos).toInt()
                    l[i] += x
                    r[i] += x
                    f++
                }
            }
            k++
        }
    }

    /** Starting patterns, written for one 4/4 bar of 16ths; tiled to fit other bar lengths. */
    val presets: List<Pair<String, List<String>>> = listOf(
        "Rock" to listOf(
            "x.......x.x.....", "....x.......x...", "x.x.x.x.x.x.x.x.", "................", "................", "................",
        ),
        "Half-time" to listOf(
            "x.........x.....", "........x.......", "x.x.x.x.x.x.x.x.", "................", "................", "...............x",
        ),
        "Shuffle" to listOf(
            "x.......x.......", "....x.......x...", "x.x.x.x.x.x.x.x.", "................", "................", "................",
        ),
        "Four on the floor" to listOf(
            "x...x...x...x...", "................", "x...x...x...x...", "..x...x...x...x.", "....x.......x...", "................",
        ),
        "Funk" to listOf(
            "x..x...x..x.....", "....x.......x...", "xxxxxxxxxxxxxxxx", "................", "................", ".......x......x.",
        ),
        "Clear" to List(VOICES) { "................" },
    )

    fun presetSwing(name: String) = if (name == "Shuffle") 1f else 0f

    /** Tiles a 16-step preset row across the song's bar length and bar count. */
    fun tile(row: String, s: Song): String {
        val total = steps(s)
        val sb = StringBuilder()
        val spb = stepsPerBar(s)
        for (i in 0 until MAX_STEPS) sb.append(if (i < total) row[(i % spb) % row.length] else '.')
        return sb.toString()
    }
}
