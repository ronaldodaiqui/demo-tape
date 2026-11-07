package app.demotape

import app.demotape.audio.Declicker
import app.demotape.audio.SR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plucked notes, a bass note and a beatbox-style kick, with clicks and a handling bump added:
 * clicks and the bump go, everything played stays.
 */
class DeclickerTest {
    private fun maxErr(a: DoubleArray, b: DoubleArray, from: Int, to: Int): Double {
        var m = 0.0
        for (i in from until to) m = maxOf(m, abs(a[i] - b[i]))
        return m
    }

    @Test
    fun clicksAndBumpGoPlayingStays() {
        val r = java.util.Random(1)
        val n = SR * 4
        val clean = DoubleArray(n) { r.nextGaussian() * 0.002 }
        val onsets = listOf(SR / 2, SR * 3 / 2, SR * 5 / 2)
        for (o in onsets) for (i in o until n) {
            val t = (i - o).toDouble() / SR
            var v = 0.0
            for (k in 1..6) v += sin(2 * PI * 196 * k * t) / k * exp(-t * (0.8 + 0.6 * k))
            clean[i] += v * 0.3
        }
        val bassAt = (SR * 3.3).toInt()
        for (i in 0 until (0.6 * SR).toInt()) {
            val t = i.toDouble() / SR
            clean[bassAt + i] += 0.3 * sin(2 * PI * 55 * t) * exp(-t / 0.5)
        }
        val kickAt = (SR * 0.2).toInt()
        for (i in 0 until (0.12 * SR).toInt()) {
            val t = i.toDouble() / SR
            clean[kickAt + i] += 0.3 * sin(2 * PI * (60 + 80 * exp(-t / 0.01)) * t) * exp(-t / 0.04) +
                (if (i < 240) r.nextGaussian() * 0.15 else 0.0)
        }
        val x = clean.copyOf()
        val clicks = listOf(SR, (SR * 1.9).toInt(), (SR * 3.1).toInt())
        for (c in clicks) {
            x[c] += 0.6; x[c + 1] -= 0.4; x[c + 2] += 0.2
        }
        val bumpAt = (SR * 2.2).toInt()
        val bumpLen = (0.08 * SR).toInt()
        for (i in 0 until bumpLen) {
            val t = i.toDouble() / SR
            x[bumpAt + i] += 0.35 * sin(2 * PI * 45 * t) * exp(-t / 0.025)
        }

        val y = x.copyOf()
        assertEquals("clicks found", 3, Declicker.declick(y))
        for (c in clicks) assertTrue("click at $c left", maxErr(y, clean, c - 3, c + 6) < 0.03)
        assertTrue("bump not found", Declicker.dethump(y) >= 1)

        // The bump's energy mostly gone.
        var eBefore = 0.0
        var eAfter = 0.0
        for (i in bumpAt until bumpAt + bumpLen) {
            eBefore += (x[i] - clean[i]).let { it * it }
            eAfter += (y[i] - clean[i]).let { it * it }
        }
        assertTrue("bump only went from ${sqrt(eBefore)} to ${sqrt(eAfter)}", eAfter < eBefore * 0.1)
        // Played things untouched.
        for (o in onsets) assertTrue("attack at $o changed", maxErr(y, clean, o, o + 480) < 0.01)
        assertTrue("bass changed", maxErr(y, clean, bassAt, bassAt + (0.6 * SR).toInt()) < 0.01)
        assertTrue("kick changed", maxErr(y, clean, kickAt, kickAt + (0.12 * SR).toInt()) < 0.01)
    }
}
