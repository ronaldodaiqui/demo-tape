package app.demotape.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ-cookbook biquad, transposed direct form II. */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }

    fun lowShelf(f: Double, db: Double) {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f / SR
        val c = cos(w)
        val al = sin(w) / 2 * sqrt(2.0)
        val k = 2 * sqrt(a) * al
        set(
            a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
            (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k,
        )
    }

    fun highShelf(f: Double, db: Double) {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f / SR
        val c = cos(w)
        val al = sin(w) / 2 * sqrt(2.0)
        val k = 2 * sqrt(a) * al
        set(
            a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
            (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k,
        )
    }

    fun lowpass(f: Double, q: Double) {
        val w = 2 * PI * f / SR
        val c = cos(w)
        val al = sin(w) / (2 * q)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }

    fun highpass(f: Double, q: Double) {
        val w = 2 * PI * f / SR
        val c = cos(w)
        val al = sin(w) / (2 * q)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }

    fun bandpass(f: Double, q: Double) {
        val w = 2 * PI * f / SR
        val c = cos(w)
        val al = sin(w) / (2 * q)
        set(al, 0.0, -al, 1 + al, -2 * c, 1 - al)
    }

    fun peak(f: Double, q: Double, db: Double) {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f / SR
        val c = cos(w)
        val al = sin(w) / (2 * q)
        set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
    }
}

/**
 * A channel's tone control, voiced for phones and earbuds rather than studio monitors:
 * 200 Hz low shelf, sweepable mid bell (200 Hz to 5 kHz), 5 kHz high shelf.
 */
class ChannelEq {
    private val lo = Biquad()
    private val mid = Biquad()
    private val hi = Biquad()

    // Smoothed settings. They glide toward the knobs a little every block, so turning a knob
    // changes the filters in small steps instead of jumps (which crackle).
    private var cLow = 0f
    private var cMid = 0f
    private var cHigh = 0f
    private var cFreq = 1000f
    private var set = false

    /** Applies the tone. Returns false (and does nothing) while everything is flat. */
    fun process(buf: FloatArray, n: Int, low: Float, m: Float, high: Float, midFreq: Float): Boolean {
        val flat = low == 0f && m == 0f && high == 0f
        if (flat && cLow == 0f && cMid == 0f && cHigh == 0f) {
            reset()
            cFreq = midFreq
            return false
        }
        val a = (n / 960f * 0.3f).coerceIn(0.05f, 1f)
        fun glide(c: Float, t: Float) = if (kotlin.math.abs(t - c) < 0.05f) t else c + (t - c) * a
        val nLow = glide(cLow, low)
        val nMid = glide(cMid, m)
        val nHigh = glide(cHigh, high)
        val nFreq = if (kotlin.math.abs(midFreq - cFreq) < 2f) midFreq
        else Math.exp(Math.log(cFreq.toDouble()) + (Math.log(midFreq.toDouble()) - Math.log(cFreq.toDouble())) * a).toFloat()
        if (!set || nLow != cLow) lo.lowShelf(200.0, nLow.toDouble())
        if (!set || nMid != cMid || nFreq != cFreq) mid.peak(nFreq.toDouble().coerceIn(100.0, 8000.0), 0.9, nMid.toDouble())
        if (!set || nHigh != cHigh) hi.highShelf(5000.0, nHigh.toDouble())
        set = true
        cLow = nLow; cMid = nMid; cHigh = nHigh; cFreq = nFreq
        // All three always run while active; a 0 dB band passes sound through unchanged.
        for (i in 0 until n) {
            buf[i] = hi.process(mid.process(lo.process(buf[i].toDouble()))).toFloat()
        }
        return true
    }

    fun reset() {
        lo.reset(); mid.reset(); hi.reset()
        cLow = 0f; cMid = 0f; cHigh = 0f
        set = false
    }
}

/** Constant-power pan, scaled so center is unity in both channels. */
fun panGains(pan: Float): Pair<Float, Float> {
    val th = (pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat()
    val s2 = sqrt(2f)
    return (cos(th) * s2) to (sin(th) * s2)
}
