package app.demotape.audio

import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * "Fix clicks & bumps" for a recorded take, done afterwards.
 *
 * Clicks (very short spikes): in each short stretch the audio is modeled by linear prediction
 * (what each sample "should" be from the ones before it). Samples that miss the prediction by
 * far more than usual, for only a moment, are a click; they're rebuilt from the audio on both
 * sides (least-squares autoregressive interpolation). A note starting also misses the
 * prediction, but it keeps going afterwards, so it's left alone.
 *
 * Bumps (handling thumps): a sudden burst of low end (under ~120 Hz) with no jump in the
 * highs, that dies away within a fraction of a second, is the phone being bumped. Only the
 * lows are turned down, only during the bump. A pluck, a kick or a bass note doesn't look
 * like that (the highs jump too, or the note keeps ringing), so it's kept.
 */
object Declicker {
    private const val P = 24 // prediction order
    private const val B = 2048 // analysis block (hop is half)
    private const val K = 7.0 // how far off (in typical errors) counts as a click
    private const val MAX_CLICK = 120 // samples (2.5 ms)

    fun process(src: ShortBuffer): ShortArray {
        val n = src.capacity()
        val y = DoubleArray(n) { src.get(it) / 32768.0 }
        declick(y)
        dethump(y)
        return ShortArray(n) { (y[it] * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }
    }

    // ---------------------------------------------------------------- clicks

    /** Repairs clicks in place; returns how many. */
    fun declick(y: DoubleArray): Int {
        val n = y.size
        if (n < B * 2) return 0
        val win = DoubleArray(B) { 0.5 - 0.5 * cos(2 * PI * it / (B - 1)) }
        val seg = DoubleArray(B)
        val e = DoubleArray(B)
        val absE = DoubleArray(B - P)
        var fixed = 0
        var s = 0
        while (s + B < n) {
            for (i in 0 until B) seg[i] = y[s + i] * win[i]
            val r = DoubleArray(P + 1) { lag ->
                var acc = 0.0
                for (i in lag until B) acc += seg[i] * seg[i - lag]
                acc
            }
            if (r[0] < 1e-10) {
                s += B / 2
                continue
            }
            r[0] *= 1.0001
            val c = levinson(r)
            for (i in 0 until B) {
                if (i < P) {
                    e[i] = 0.0
                    continue
                }
                var acc = 0.0
                for (k in 0..P) acc += c[k] * y[s + i - k]
                e[i] = acc
            }
            for (i in P until B) absE[i - P] = abs(e[i])
            val sorted = absE.copyOf().also { it.sort() }
            val sigma = sorted[sorted.size / 2] * 1.4826 + 1e-6
            val lim = max(K * sigma, 0.004)

            // Group flagged samples into events.
            var i = P
            while (i < B) {
                if (abs(e[i]) <= lim) {
                    i++
                    continue
                }
                val a = i
                var b = i
                var j = i + 1
                while (j < B && j - b <= 8) {
                    if (abs(e[j]) > lim) b = j
                    j++
                }
                i = b + 1
                val a0 = s + a - 2
                val b0 = s + b + 3
                val m = b0 - a0
                if (m > MAX_CLICK || a0 - P - 480 < 0 || b0 + P + 540 >= n) continue
                // A note starting keeps going (energy stays up); a click doesn't.
                val pre = rms(y, a0 - 480, a0) + 1e-6
                val post = rms(y, b0 + 60, b0 + 540) + 1e-6
                if (post > pre * 2.5) continue
                if (repair(y, c, a0, b0)) fixed++
            }
            s += B / 2
        }
        return fixed
    }

    private fun rms(y: DoubleArray, a: Int, b: Int): Double {
        var e = 0.0
        for (i in a until b) e += y[i] * y[i]
        return sqrt(e / (b - a))
    }

    /** Prediction-error filter coefficients (c[0] = 1) from autocorrelation [r]. */
    private fun levinson(r: DoubleArray): DoubleArray {
        val a = DoubleArray(P + 1)
        a[0] = 1.0
        var err = r[0]
        for (i in 1..P) {
            var acc = r[i]
            for (j in 1 until i) acc += a[j] * r[i - j]
            val k = if (err > 0) -acc / err else 0.0
            val prev = a.copyOf()
            for (j in 1 until i) a[j] = prev[j] + k * prev[i - j]
            a[i] = k
            err *= (1 - k * k)
            if (err <= 1e-12) break
        }
        return a
    }

    /**
     * Rebuilds y[a0 until b0] so the prediction error around it is as small as possible
     * (least-squares autoregressive interpolation).
     */
    private fun repair(y: DoubleArray, c: DoubleArray, a0: Int, b0: Int): Boolean {
        val m = b0 - a0
        val l0 = a0 - P
        val rows = (b0 + P) - l0 - P
        // Normal equations: (AᵀA) x = -Aᵀb
        val ata = Array(m) { DoubleArray(m) }
        val atb = DoubleArray(m)
        val row = DoubleArray(m)
        for (ri in 0 until rows) {
            val t = l0 + P + ri
            row.fill(0.0)
            var bv = 0.0
            for (k in 0..P) {
                val u = t - k
                if (u in a0 until b0) row[u - a0] += c[k] else bv += c[k] * y[u]
            }
            for (p in 0 until m) {
                if (row[p] == 0.0) continue
                atb[p] += row[p] * bv
                for (q in 0 until m) ata[p][q] += row[p] * row[q]
            }
        }
        for (p in 0 until m) ata[p][p] += 1e-9
        val x = solve(ata, DoubleArray(m) { -atb[it] }) ?: return false
        for (p in 0 until m) y[a0 + p] = x[p]
        return true
    }

    /** Gaussian elimination with partial pivoting. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-14) return null
            if (piv != col) {
                val t = a[piv]; a[piv] = a[col]; a[col] = t
                val tb = b[piv]; b[piv] = b[col]; b[col] = tb
            }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (k in col until n) a[r][k] -= f * a[col][k]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var acc = b[r]
            for (k in r + 1 until n) acc -= a[r][k] * x[k]
            x[r] = acc / a[r][r]
        }
        return x
    }

    // ---------------------------------------------------------------- bumps

    /** Turns down handling thumps in place; returns how many. */
    fun dethump(y: DoubleArray): Int {
        val n = y.size
        if (n < SR) return 0
        val low = lowpassZeroPhase(y, 120.0)
        val lowEnv = DoubleArray(n) { abs(low[it]) }
        val hiEnv = DoubleArray(n) { abs(y[it] - low[it]) }
        val fast = smooth(lowEnv, 5.0)
        val slow = smooth(lowEnv, 600.0)
        val hFast = smooth(hiEnv, 5.0)
        val hSlow = smooth(hiEnv, 600.0)
        val hJump = BooleanArray(n) { hFast[it] > 2 * hSlow[it] + 0.003 }
        val cand = BooleanArray(n) { fast[it] > 4 * slow[it] + 0.003 && !hJump[it] }

        val g = DoubleArray(n)
        var count = 0
        var i = 0
        while (i < n) {
            if (!cand[i]) {
                i++
                continue
            }
            var j = i
            var peak = 0.0
            while (j < n && cand[j]) {
                peak = max(peak, fast[j])
                j++
            }
            val later = min(n - 1, j + (0.15 * SR).toInt())
            val played = (max(0, i - (0.03 * SR).toInt()) until min(n, i + (0.015 * SR).toInt())).any { hJump[it] }
            if (j - i <= (0.2 * SR).toInt() && fast[later] < 0.3 * peak && !played) {
                val a = max(0, i - (0.01 * SR).toInt())
                val b = min(n, j + (0.04 * SR).toInt())
                for (k in a until b) g[k] = 1.0
                count++
            }
            i = j
        }
        if (count == 0) return 0
        // Soften the edges (10 ms), then take most of the lows out where it's a bump.
        val k = (0.01 * SR).toInt()
        var acc = 0.0
        val gs = DoubleArray(n)
        for (t in 0 until n + k / 2) {
            if (t < n) acc += g[t]
            if (t - k >= 0) acc -= g[t - k]
            val c = t - k / 2
            if (c in 0 until n) gs[c] = acc / k
        }
        for (t in 0 until n) y[t] -= 0.9 * gs[t] * low[t]
        return count
    }

    private fun smooth(v: DoubleArray, ms: Double): DoubleArray {
        val a = exp(-1.0 / (SR * ms / 1000.0))
        val out = DoubleArray(v.size)
        var s = 0.0
        for (i in v.indices) {
            s = a * s + (1 - a) * v[i]
            out[i] = s
        }
        return out
    }

    /** 2nd-order Butterworth low-pass, run forward then backward (no phase shift). */
    private fun lowpassZeroPhase(x: DoubleArray, fc: Double): DoubleArray {
        val w = tan(PI * fc / SR)
        val k = w * w
        val q = sqrt(2.0)
        val norm = 1 + q * w + k
        val b0 = k / norm
        val b1 = 2 * b0
        val b2 = b0
        val a1 = 2 * (k - 1) / norm
        val a2 = (1 - q * w + k) / norm
        fun run(v: DoubleArray, reverse: Boolean): DoubleArray {
            val out = DoubleArray(v.size)
            var z1 = 0.0
            var z2 = 0.0
            val idx = if (reverse) v.indices.reversed() else v.indices
            for (i in idx) {
                val xx = v[i]
                val yy = b0 * xx + z1
                z1 = b1 * xx - a1 * yy + z2
                z2 = b2 * xx - a2 * yy
                out[i] = yy
            }
            return out
        }
        return run(run(x, false), true)
    }
}
