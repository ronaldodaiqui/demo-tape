package app.demotape.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
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

    /** How much this filter boosts or cuts at [f] Hz, in dB. */
    fun magnitudeDb(f: Double): Double {
        val w = 2 * PI * f / SR
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2
        val di = -(a1 * s1 + a2 * s2)
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10 * kotlin.math.log10(mag2.coerceAtLeast(1e-12))
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
 * A channel's 6-band EQ: a low shelf at 80 Hz, broad bells at 200 Hz, 500 Hz, 1.2 kHz and
 * 3 kHz, and a high shelf at 8 kHz. Each band is -12..+12 dB. Few, broad bands on purpose:
 * enough to shape a part, not so many that it turns into tweaking.
 */
class GraphicEq {
    private val bands = Array(BANDS) { Biquad() }

    // Smoothed gains. They glide toward the sliders a little every block, so moving a slider
    // changes the filters in small steps instead of jumps (which crackle).
    private val cur = FloatArray(BANDS)
    private var set = false

    /** Applies the EQ. Returns false (and does nothing) while everything is flat. */
    fun process(buf: FloatArray, n: Int, gains: List<Float>) {
        val flat = (0 until BANDS).all { (gains.getOrNull(it) ?: 0f) == 0f }
        if (flat && cur.all { it == 0f }) {
            reset()
            return
        }
        val a = (n / 960f * 0.3f).coerceIn(0.05f, 1f)
        for (i in 0 until BANDS) {
            val t = gains.getOrNull(i) ?: 0f
            val c = cur[i]
            val nv = if (kotlin.math.abs(t - c) < 0.05f) t else c + (t - c) * a
            if (!set || nv != c) design(bands[i], i, nv.toDouble())
            cur[i] = nv
        }
        set = true
        for (j in 0 until n) {
            var x = buf[j].toDouble()
            for (bq in bands) x = bq.process(x)
            buf[j] = x.toFloat()
        }
    }

    fun reset() {
        bands.forEach { it.reset() }
        cur.fill(0f)
        set = false
    }

    companion object {
        const val BANDS = 6
        const val RANGE = 12f
        val FREQS = doubleArrayOf(80.0, 200.0, 500.0, 1250.0, 3150.0, 8000.0)
        val LABELS = listOf("80", "200", "500", "1.2k", "3k", "8k")

        /** Bands are spaced this far apart (a factor of 2.5, about 1⅓ octaves). */
        const val STEP = 2.5
        val FLAT: List<Float> = List(BANDS) { 0f }

        private fun design(bq: Biquad, i: Int, db: Double) {
            when (i) {
                0 -> bq.lowShelf(FREQS[0], db)
                BANDS - 1 -> bq.highShelf(FREQS[i], db)
                else -> bq.peak(FREQS[i], 0.9, db)
            }
        }

        /** The EQ's overall boost or cut at [f] Hz, in dB (for drawing its curve). */
        fun responseDb(gains: List<Float>, f: Double): Double {
            var db = 0.0
            val bq = Biquad()
            for (i in 0 until BANDS) {
                val g = gains.getOrNull(i) ?: 0f
                if (g == 0f) continue
                design(bq, i, g.toDouble())
                db += bq.magnitudeDb(f)
            }
            return db
        }

        /** Settings from the short-lived 8-band EQ, matched as closely as 6 bands can. */
        fun fromEight(g8: List<Float>): List<Float> {
            val f8 = doubleArrayOf(80.0, 160.0, 320.0, 640.0, 1250.0, 2500.0, 5000.0, 10000.0)
            val filters = g8.mapIndexedNotNull { i, g ->
                if (g == 0f || i >= f8.size) null else Biquad().apply {
                    when (i) {
                        0 -> lowShelf(f8[0], g.toDouble())
                        7 -> highShelf(f8[7], g.toDouble())
                        else -> peak(f8[i], 1.2, g.toDouble())
                    }
                }
            }
            return FREQS.map { f ->
                filters.sumOf { it.magnitudeDb(f) }.toFloat().coerceIn(-RANGE, RANGE).let { (it * 2).roundToInt() / 2f }
            }
        }

        /** Settings from the old three-knob tone control, matched as closely as the bands can. */
        fun fromTone(low: Float, mid: Float, high: Float, midFreq: Float): List<Float> {
            val lo = Biquad().apply { lowShelf(200.0, low.toDouble()) }
            val md = Biquad().apply { peak(midFreq.toDouble().coerceIn(100.0, 8000.0), 0.9, mid.toDouble()) }
            val hi = Biquad().apply { highShelf(5000.0, high.toDouble()) }
            return FREQS.map { f ->
                (lo.magnitudeDb(f) + md.magnitudeDb(f) + hi.magnitudeDb(f)).toFloat().coerceIn(-RANGE, RANGE)
                    .let { (it * 2).roundToInt() / 2f }
            }
        }
    }
}

/** Constant-power pan, scaled so center is unity in both channels. */
fun panGains(pan: Float): Pair<Float, Float> {
    val th = (pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat()
    val s2 = sqrt(2f)
    return (cos(th) * s2) to (sin(th) * s2)
}
