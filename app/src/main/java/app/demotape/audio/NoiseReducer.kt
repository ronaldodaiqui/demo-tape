package app.demotape.audio

import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Noise reduction for a recorded take, done afterwards (like Audacity's): spectral gating.
 *
 * 1. Find the take's quietest half second (the moment before you start playing, a gap between
 *    phrases) and measure the noise there, frequency by frequency.
 * 2. Go through the take in short overlapping slices. Wherever a frequency isn't clearly above
 *    the noise, turn it down (only down by so much: [Strength.floorDb]). Gains are smoothed
 *    across neighboring frequencies and over time, which avoids the chirpy "musical noise"
 *    and the watery sound of crude gating.
 */
object NoiseReducer {
    enum class Strength(val label: String, val over: Double, val floorDb: Double) {
        LIGHT("Light", 1.6, -10.0),
        MEDIUM("Medium", 2.2, -16.0),
        STRONG("Strong", 3.0, -24.0),
    }

    private const val N = 2048
    private const val HOP = N / 4
    private const val BINS = N / 2 + 1

    /** Cleans [src] (mono 16-bit) and returns the result, the same length. */
    fun process(src: ShortBuffer, strength: Strength, progress: (Float) -> Unit = {}): ShortArray {
        val len = src.capacity()
        val x = FloatArray(len) { src.get(it) / 32768f }
        val out = FloatArray(len + N)
        if (len < N) return ShortArray(len) { src.get(it) }

        val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
        val profile = noiseProfile(x, window) ?: return ShortArray(len) { src.get(it) }
        val thr = DoubleArray(BINS) { profile[it] * strength.over }
        val floor = 10.0.pow(strength.floorDb / 20)

        val re = DoubleArray(N)
        val im = DoubleArray(N)
        val gain = DoubleArray(BINS)
        val prev = DoubleArray(BINS) { 1.0 }
        val smooth = DoubleArray(BINS)
        // Gains can rise at once (so note attacks come through) but fall back slowly.
        val release = 0.55
        // Hann analysis and synthesis at 75% overlap add up to 1.5.
        val norm = 1f / 1.5f
        val frames = (len - N) / HOP + 2
        var f = 0
        var pos = -HOP * 2 // start a little before the beginning so the first samples are covered
        while (pos < len) {
            for (i in 0 until N) {
                val j = pos + i
                re[i] = if (j in 0 until len) (x[j] * window[i]).toDouble() else 0.0
                im[i] = 0.0
            }
            fft(re, im, false)
            for (k in 0 until BINS) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                // Soft gate: about 1 well above the noise, down to the floor at or below it.
                val r = if (mag > 1e-12) thr[k] / mag else 1e9
                gain[k] = max(floor, min(1.0, 1.0 - r * r))
            }
            // Smooth across neighboring frequencies (a little) ...
            for (k in 0 until BINS) {
                var s = 0.0
                var c = 0
                for (d in -2..2) {
                    val kk = k + d
                    if (kk in 0 until BINS) {
                        s += gain[kk]
                        c++
                    }
                }
                smooth[k] = s / c
            }
            // ... and over time.
            for (k in 0 until BINS) {
                val g = max(smooth[k], prev[k] * release + floor * (1 - release))
                prev[k] = g
                val gg = g
                re[k] *= gg
                im[k] *= gg
                if (k in 1 until N / 2) {
                    re[N - k] *= gg
                    im[N - k] *= gg
                }
            }
            fft(re, im, true)
            for (i in 0 until N) {
                val j = pos + i
                if (j in 0 until len) out[j] += (re[i] * window[i]).toFloat() * norm
            }
            pos += HOP
            f++
            if (f % 64 == 0) progress((f.toFloat() / frames).coerceAtMost(1f))
        }
        return ShortArray(len) { (out[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /**
     * The noise's typical level per frequency, from the quietest half second of the take
     * (ignoring digital silence). Null if the take is too short to tell.
     */
    private fun noiseProfile(x: FloatArray, window: FloatArray): DoubleArray? {
        val len = x.size
        val span = min(SR / 2, len)
        val step = SR / 20
        var best = -1
        var bestE = Double.MAX_VALUE
        var i = 0
        while (i + span <= len) {
            var e = 0.0
            var j = i
            while (j < i + span) {
                e += x[j] * x[j]
                j += 4
            }
            e /= (span / 4)
            if (e > 1e-10 && e < bestE) {
                bestE = e
                best = i
            }
            i += step
        }
        if (best < 0) return null
        val prof = DoubleArray(BINS)
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        var count = 0
        var p = best
        while (p + N <= best + span || count == 0) {
            if (p + N > len) break
            for (k in 0 until N) {
                re[k] = (x[p + k] * window[k]).toDouble()
                im[k] = 0.0
            }
            fft(re, im, false)
            for (k in 0 until BINS) prof[k] += sqrt(re[k] * re[k] + im[k] * im[k])
            count++
            p += HOP
        }
        if (count == 0) return null
        for (k in 0 until BINS) prof[k] /= count
        return prof
    }

    /** In-place radix-2 FFT (inverse includes the 1/N scale). */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val ang = 2 * PI / size * (if (inverse) 1 else -1)
            val wr = cos(ang)
            val wi = sin(ang)
            var start = 0
            while (start < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until size / 2) {
                    val a = start + k
                    val b = a + size / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                start += size
            }
            size = size shl 1
        }
        if (inverse) for (i in 0 until n) {
            re[i] /= n
            im[i] /= n
        }
    }
}
