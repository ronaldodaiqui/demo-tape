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
 * A small step-sequencer drum machine voiced like the early preset rhythm boxes (CR-78, organ-top
 * rhythm units): soft round kick, gentle snare, quiet noise hats and bongo-like toms. Meant to sit
 * back in the mix like an editable metronome. Synthesized once at startup (no sample files),
 * 16th-note steps on the metronome's grid, one or two bars, with swing.
 */
object Drums {
    const val VOICES = 6
    const val MAX_STEPS = 64
    val NAMES = listOf("Kick", "Snare", "Hat", "Open", "Lo tom", "Hi tom")
    private const val HAT = 2
    private const val OPEN = 3

    private val rng = java.util.Random(78)
    private fun noise() = rng.nextDouble() * 2 - 1

    private fun len(sec: Double) = (SR * sec).toInt()

    /**
     * Darkens (vintage machines rolled off the top end), peak-normalizes to [level], and fades the
     * tail smoothly to zero so nothing is ever cut off mid-decay.
     */
    private fun finish(x: DoubleArray, level: Double, lowpassHz: Double = 7000.0): FloatArray {
        val lp1 = Biquad().apply { lowpass(lowpassHz, 0.6) }
        val lp2 = Biquad().apply { lowpass(lowpassHz, 0.6) }
        for (i in x.indices) x[i] = lp2.process(lp1.process(x[i]))
        var pk = 1e-9
        for (v in x) pk = max(pk, abs(v))
        val n = x.size
        val tail = n / 3
        return FloatArray(n) { i ->
            var v = x[i] / pk * level
            if (i >= n - tail) {
                val p = (i - (n - tail)).toDouble() / tail
                v *= 0.5 * (1 + kotlin.math.cos(PI * p))
            }
            v.toFloat()
        }
    }

    /** Resonant "bridged-T" drum: a sine ringing down from a small pitch blip. */
    private fun ring(f0: Double, blip: Double, decay: Double): DoubleArray {
        val n = len(decay * 7)
        var ph = 0.0
        return DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            ph += 2 * PI * (f0 + blip * exp(-t / 0.01)) / SR
            // a few samples of soft attack so it starts without a click
            sin(ph) * exp(-t / decay) * (1 - exp(-t / 0.0006))
        }
    }

    private val kick: FloatArray = finish(ring(68.0, 22.0, 0.11), 0.6, lowpassHz = 2500.0)

    private val snare: FloatArray = run {
        val tone = ring(215.0, 30.0, 0.035)
        val bp = Biquad().apply { bandpass(2600.0, 0.7) }
        val n = len(0.32)
        val x = DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            val nz = bp.process(noise()) * exp(-t / 0.07) * (1 - exp(-t / 0.0008))
            (if (i < tone.size) tone[i] * 0.55 else 0.0) + nz * 0.9
        }
        finish(x, 0.42, lowpassHz = 6000.0)
    }

    private fun hiss(decay: Double, level: Double): FloatArray {
        val hp = Biquad().apply { highpass(6000.0, 0.7) }
        val n = len(decay * 7)
        val x = DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            hp.process(noise()) * exp(-t / decay) * (1 - exp(-t / 0.0005))
        }
        return finish(x, level, lowpassHz = 11000.0)
    }

    private val hat = hiss(0.016, 0.2)
    private val open = hiss(0.1, 0.17)

    private val loTom = finish(ring(165.0, 25.0, 0.075), 0.38, lowpassHz = 3500.0)
    private val hiTom = finish(ring(245.0, 30.0, 0.06), 0.34, lowpassHz = 4000.0)

    private val sounds = arrayOf(kick, snare, hat, open, loTom, hiTom)
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
                val fadeFrom = at + size - 240
                while (f < b) {
                    var x = snd[(f - at).toInt()] * gain
                    if (f > fadeFrom && size < snd.size) x *= (at + size - f) / 240f
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
            "x.........x.....", "........x.......", "x.x.x.x.x.x.x.x.", "................", "................", "................",
        ),
        "Shuffle" to listOf(
            "x.......x.......", "....x.......x...", "x.x.x.x.x.x.x.x.", "................", "................", "................",
        ),
        "Four on the floor" to listOf(
            "x...x...x...x...", "....x.......x...", "x...x...x...x...", "..x...x...x...x.", "................", "................",
        ),
        "Funk" to listOf(
            "x..x...x..x.....", "....x.......x..x", "xxxxxxxxxxxxxxxx", "................", "................", "................",
        ),
        "Tom groove" to listOf(
            "x.....x...x.....", "....x.......x...", "x.x.x.x.x.x.x.x.", "................", "..........x..x..", "...x..x.........",
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
