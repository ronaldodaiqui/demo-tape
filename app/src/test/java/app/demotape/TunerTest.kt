package app.demotape

import app.demotape.audio.PitchTracker
import app.demotape.audio.SR
import app.demotape.audio.TunerReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** The tuner against synthetic plucked strings at known pitches: right note, right cents, steady. */
class TunerTest {
    /**
     * A plucked string: a noisy attack, starts a little sharp and settles, harmonics that die
     * away faster than the fundamental, plus a bit of room noise.
     */
    private fun pluck(f0: Double, sec: Double, seed: Long): ShortArray {
        val r = java.util.Random(seed)
        val n = (sec * SR).toInt()
        val out = ShortArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i / SR.toDouble()
            val f = f0 * 2.0.pow(15.0 * exp(-t / 0.04) / 1200.0)
            ph += 2 * PI * f / SR
            var v = 0.0
            for (k in 1..6) v += sin(k * ph) / k * exp(-t * (0.6 + 0.5 * k))
            v *= 0.45
            if (t < 0.01) v += (r.nextDouble() - 0.5) * 0.6
            v += (r.nextDouble() - 0.5) * 0.004
            out[i] = (v * 32767).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    /** Feeds the sound in 20 ms pieces, reading every 40 ms like the app; returns readings after [from] s. */
    private fun run(pcm: ShortArray, from: Double): List<TunerReading?> {
        val t = PitchTracker()
        val hop = SR / 50
        val out = ArrayList<TunerReading?>()
        var i = 0
        var ms = 0L
        while (i + hop <= pcm.size) {
            t.push(pcm.copyOfRange(i, i + hop), hop)
            i += hop
            ms += 20
            if (ms % 40 == 0L) {
                val r = t.update(ms)
                if (ms / 1000.0 >= from) out += r
            }
        }
        return out
    }

    private fun check(f0: Double, note: String, octave: Int, cents: Float) {
        val rs = run(pluck(f0, 2.2, f0.toLong()), from = 0.6)
        assertTrue("no reading for $note$octave", rs.all { it != null })
        val r = rs.filterNotNull()
        r.forEach { assertEquals("$note$octave", "${it.note}${it.octave}") }
        val c = r.map { it.cents }
        val mean = c.average().toFloat()
        val spread = c.max() - c.min()
        assertEquals("cents for $note$octave", cents, mean, 1.5f)
        assertTrue("wobble for $note$octave: $spread cents", spread < 2f)
    }

    @Test fun lowE() = check(82.41, "E", 2, 0f)
    @Test fun a() = check(110.0, "A", 2, 0f)
    @Test fun d() = check(146.83, "D", 3, 0f)
    @Test fun g() = check(196.0, "G", 3, 0f)
    @Test fun b() = check(246.94, "B", 3, 0f)
    @Test fun highE() = check(329.63, "E", 4, 0f)
    @Test fun a440() = check(440.0, "A", 4, 0f)

    @Test fun slightlySharpA() = check(110.0 * 2.0.pow(8.0 / 1200), "A", 2, 8f)
    @Test fun slightlyFlatLowE() = check(82.41 * 2.0.pow(-12.0 / 1200), "E", 2, -12f)
}
