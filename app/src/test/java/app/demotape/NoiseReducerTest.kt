package app.demotape

import app.demotape.audio.NoiseReducer
import app.demotape.audio.SR
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Noise reduction on a known signal: hiss goes down a lot, the playing barely changes. */
class NoiseReducerTest {
    private fun rmsDb(a: ShortArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += (a[i] / 32768.0).let { it * it }
        return 20 * log10(sqrt(e / (to - from)) + 1e-12)
    }

    @Test
    fun hissDownToneKept() {
        val r = java.util.Random(1)
        val n = SR * 4
        // One second of hiss alone (like the moment before you play), then a note over the hiss.
        val pcm = ShortArray(n) { i ->
            val hiss = r.nextGaussian() * 0.006
            val tone = if (i >= SR) 0.25 * (sin(2 * PI * 220.0 * i / SR) + 0.5 * sin(2 * PI * 440.0 * i / SR)) else 0.0
            ((hiss + tone) * 32767).toInt().coerceIn(-32767, 32767).toShort()
        }
        for (s in NoiseReducer.Strength.values()) {
            val out = NoiseReducer.process(ShortBuffer.wrap(pcm), s)
            val hissBefore = rmsDb(pcm, SR / 10, SR * 9 / 10)
            val hissAfter = rmsDb(out, SR / 10, SR * 9 / 10)
            val toneBefore = rmsDb(pcm, SR * 2, SR * 3)
            val toneAfter = rmsDb(out, SR * 2, SR * 3)
            assertTrue("$s: hiss only went from %.1f to %.1f dB".format(hissBefore, hissAfter), hissAfter < hissBefore - 8)
            assertTrue("$s: tone changed from %.1f to %.1f dB".format(toneBefore, toneAfter), kotlin.math.abs(toneAfter - toneBefore) < 1.0)
        }
    }
}
