package app.demotape.audio

import kotlin.math.abs
import kotlin.math.max

/**
 * Tap-along sync check: the app plays clicks, the player knocks on the phone in time,
 * and the mic hears the knocks. How late the knocks land (after the automatic timing
 * correction) is the extra delay to remove from future takes on those headphones.
 */
object Calibration {
    const val COUNT = 8
    const val PERIOD = SR * 2 / 3 // 90 BPM: wide enough that a late knock can't match the next click

    sealed class Result {
        data class Ok(val offset: Long, val delayMs: Int) : Result()
        data class Fail(val reason: String) : Result()
    }

    fun clicks(): LongArray = LongArray(COUNT) { SR.toLong() + it.toLong() * PERIOD }

    /** Sample positions of sharp transients (knocks) in [x]. */
    fun onsets(x: ShortArray, n: Int): LongArray {
        val hop = 96 // 2 ms
        val hops = n / hop
        if (hops < 20) return LongArray(0)
        // High-passed (first difference) peak per hop: knocks are sharp, voices and hum aren't.
        val e = FloatArray(hops)
        var prev = x[0].toInt()
        for (h in 0 until hops) {
            var m = 0
            for (i in h * hop until (h + 1) * hop) {
                val v = x[i].toInt()
                val d = abs(v - prev)
                prev = v
                if (d > m) m = d
            }
            e[h] = m.toFloat()
        }
        val sorted = e.copyOf().also { it.sort() }
        val floor = sorted[hops / 2]
        val top = sorted[minOf(hops - 1, (hops * 0.995).toInt())]
        val thr = max(max(floor * 6f, floor + (top - floor) * 0.25f), 150f)
        val refractory = SR * 15 / 100 / hop
        val out = ArrayList<Long>()
        var last = -refractory * 2
        for (h in 1 until hops) {
            if (e[h] >= thr && e[h - 1] < thr && h - last > refractory) {
                var pos = h * hop
                val from = max(1, (h - 1) * hop)
                for (i in from until (h + 1) * hop) {
                    if (abs(x[i] - x[i - 1]) >= thr * 0.5f) {
                        pos = i
                        break
                    }
                }
                out.add(pos.toLong())
                last = h
            }
        }
        return out.toLongArray()
    }

    /**
     * Matches knocks to clicks. A knock may land up to 150 ms early (people anticipate)
     * or 500 ms late (unreported Bluetooth delay).
     */
    fun evaluate(onsets: LongArray, clicks: LongArray, baseDelay: Long): Result {
        val early = SR * 15L / 100
        val late = SR / 2L
        val res = ArrayList<Long>()
        for (c in clicks) {
            val k = onsets.filter { it >= c - early && it <= c + late }.minByOrNull { abs(it - c) } ?: continue
            res.add(k - c)
        }
        if (res.size < 5) {
            return Result.Fail(
                if (res.isEmpty()) "Didn't hear any knocks. Knock a bit harder, near the bottom of the phone."
                else "Only caught ${res.size} of $COUNT knocks. Knock a bit harder and keep going until the clicks stop."
            )
        }
        res.sort()
        val med = res[res.size / 2]
        val dev = res.map { abs(it - med) }.sorted()
        val mad = dev[dev.size / 2]
        if (mad > SR * 3L / 100) {
            return Result.Fail("The knocks were a little uneven. Try again and lock in with the clicks.")
        }
        val delayMs = ((baseDelay + med) * 1000 / SR).toInt()
        return Result.Ok(offset = med, delayMs = delayMs)
    }
}
