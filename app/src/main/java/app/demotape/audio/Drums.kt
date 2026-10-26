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
 * A small step-sequencer drum machine voiced after the Roland TR-606: tight kick, snappy snare,
 * sizzly square-wave hats and two toms. Synthesized once at startup (no sample files),
 * 16th-note steps on the metronome's grid, one or two bars, with swing.
 */
object Drums {
    const val VOICES = 6
    const val MAX_STEPS = 64
    val NAMES = listOf("Kick", "Snare", "Hat", "Open", "Lo tom", "Hi tom")
    private const val HAT = 2
    private const val OPEN = 3

    private val rng = java.util.Random(606)
    private fun noise() = rng.nextDouble() * 2 - 1

    /** Peak-normalizes, then runs a little analog-style saturation for vintage grit. */
    private fun finish(x: DoubleArray, level: Double, drive: Double = 1.4): FloatArray {
        var pk = 1e-9
        for (v in x) pk = max(pk, abs(v))
        val norm = tanh(drive)
        return FloatArray(x.size) { (tanh(x[it] / pk * drive) / norm * level).toFloat() }
    }

    private fun len(sec: Double) = (SR * sec).toInt()

    /** Bridged-T style drum: a sine with a quick pitch blip, short decay, and a trigger click. */
    private fun bridgedT(f0: Double, blip: Double, decay: Double, sec: Double, click: Double): DoubleArray {
        val n = len(sec)
        var ph = 0.0
        return DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            val f = f0 + blip * exp(-t / 0.006)
            ph += 2 * PI * f / SR
            val env = exp(-t / decay)
            sin(ph) * env + click * exp(-t / 0.0007) * (if (i % 2 == 0) 1.0 else -0.6)
        }
    }

    // 606 kick: higher and much shorter than an 808, more "thup" than boom.
    private val kick: FloatArray = finish(bridgedT(62.0, 90.0, 0.085, 0.3, 0.35), 0.95, drive = 1.8)

    // 606 snare: a quick tonal body under bright, hissy noise.
    private val snare: FloatArray = run {
        val n = len(0.25)
        val hp = Biquad().apply { highpass(1800.0, 0.7) }
        val lp = Biquad().apply { peak(6500.0, 0.8, 4.0) }
        var ph1 = 0.0
        var ph2 = 0.0
        val x = DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            ph1 += 2 * PI * (238 + 40 * exp(-t / 0.005)) / SR
            ph2 += 2 * PI * 476.0 / SR
            val tone = (sin(ph1) * 0.7 + sin(ph2) * 0.25) * exp(-t / 0.028)
            val nz = lp.process(hp.process(noise())) * exp(-t / 0.075)
            0.6 * tone + 0.9 * nz
        }
        finish(x, 0.8, drive = 1.6)
    }

    // 606 metal: six square-wave oscillators through a band-pass, brighter and thinner than 808.
    private fun metal(decay: Double, sec: Double, level: Double): FloatArray {
        val n = len(sec)
        val freqs = doubleArrayOf(245.0, 306.0, 365.0, 415.0, 437.0, 619.0).map { it * 1.9 }
        val bp = Biquad().apply { bandpass(8800.0, 0.9) }
        val hp = Biquad().apply { highpass(6500.0, 0.7) }
        val ph = DoubleArray(freqs.size)
        val x = DoubleArray(n) { i ->
            val t = i / SR.toDouble()
            var sq = 0.0
            for (j in freqs.indices) {
                ph[j] += freqs[j] / SR
                sq += if (ph[j] % 1.0 < 0.5) 1.0 else -1.0
            }
            val v = hp.process(bp.process(sq / 6 + noise() * 0.15))
            // 606 hats have a fast attack and a slightly curved decay
            v * exp(-t / decay) * (1 - exp(-t / 0.0004))
        }
        return finish(x, level, drive = 1.2)
    }

    private val hat = metal(0.022, 0.12, 0.42)
    private val open = metal(0.16, 0.55, 0.38)

    // 606 toms: tuned bridged-T sines with a little noise on the attack.
    private fun tom(f: Double, decay: Double): FloatArray {
        val body = bridgedT(f, f * 0.35, decay, decay * 5, 0.15)
        val hp = Biquad().apply { highpass(2000.0, 0.7) }
        for (i in body.indices) {
            val t = i / SR.toDouble()
            body[i] += hp.process(noise()) * exp(-t / 0.006) * 0.25
        }
        return finish(body, 0.75, drive = 1.5)
    }

    private val loTom = tom(118.0, 0.13)
    private val hiTom = tom(176.0, 0.11)

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
