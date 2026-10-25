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
    private var cur = floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)

    fun active(low: Float, m: Float, high: Float) = low != 0f || m != 0f || high != 0f

    fun process(buf: FloatArray, n: Int, low: Float, m: Float, high: Float, midFreq: Float) {
        if (cur[0] != low) { lo.lowShelf(200.0, low.toDouble()); cur[0] = low }
        if (cur[1] != m || cur[3] != midFreq) {
            mid.peak(midFreq.toDouble().coerceIn(100.0, 8000.0), 0.9, m.toDouble())
            cur[1] = m
            cur[3] = midFreq
        }
        if (cur[2] != high) { hi.highShelf(5000.0, high.toDouble()); cur[2] = high }
        for (i in 0 until n) {
            var x = buf[i].toDouble()
            if (low != 0f) x = lo.process(x)
            if (m != 0f) x = mid.process(x)
            if (high != 0f) x = hi.process(x)
            buf[i] = x.toFloat()
        }
    }

    fun reset() {
        lo.reset(); mid.reset(); hi.reset()
    }
}

/** Constant-power pan, scaled so center is unity in both channels. */
fun panGains(pan: Float): Pair<Float, Float> {
    val th = (pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat()
    val s2 = sqrt(2f)
    return (cos(th) * s2) to (sin(th) * s2)
}
