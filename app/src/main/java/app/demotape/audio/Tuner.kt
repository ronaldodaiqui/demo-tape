package app.demotape.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the tuner hears: nearest note, how far off it is, and the frequency. */
data class TunerReading(val note: String, val octave: Int, val cents: Float, val hz: Float)

/**
 * Chromatic tuner. Listens to the mic through [AudioEngine.tap] and runs YIN pitch detection
 * about 16 times a second on the latest 4096 samples (finds 40 Hz to 1.5 kHz).
 */
class Tuner(private val engine: AudioEngine) {
    val reading = MutableStateFlow<TunerReading?>(null)

    @Volatile
    var a4 = 440f

    private val ring = ShortArray(N)
    private var written = 0L
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        engine.tap = { b, n ->
            synchronized(ring) {
                for (i in 0 until n) {
                    ring[((written + i) % N).toInt()] = b[i]
                }
                written += n
            }
        }
        job = scope.launch(Dispatchers.Default) {
            val x = FloatArray(N)
            var lastHeard = 0L
            while (isActive) {
                delay(60)
                val have = synchronized(ring) {
                    if (written < N) return@synchronized false
                    val start = written % N
                    for (i in 0 until N) x[i] = ring[((start + i) % N).toInt()] / 32768f
                    true
                }
                if (!have) continue
                val hz = detect(x)
                val now = System.currentTimeMillis()
                if (hz != null) {
                    lastHeard = now
                    reading.value = toReading(hz, a4)
                } else if (now - lastHeard > 1200) {
                    reading.value = null
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        engine.tap = null
        reading.value = null
    }

    companion object {
        private const val N = 4096
        private const val W = 2048
        private const val TAU_MIN = SR / 1500
        private const val TAU_MAX = SR / 40
        private val NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

        fun toReading(hz: Float, a4: Float): TunerReading {
            val midi = 69.0 + 12.0 * ln(hz / a4.toDouble()) / ln(2.0)
            val n = midi.roundToInt()
            val cents = ((midi - n) * 100).toFloat()
            return TunerReading(NAMES[Math.floorMod(n, 12)], n / 12 - 1, cents, hz)
        }

        /** YIN. Returns the fundamental in Hz, or null if there's no clear pitch. */
        fun detect(x: FloatArray): Float? {
            // Too quiet: nothing to tune.
            var e = 0.0
            for (i in 0 until W) e += x[i] * x[i]
            if (sqrt(e / W) < 0.006) return null

            val d = FloatArray(TAU_MAX + 2)
            for (tau in 1..TAU_MAX + 1) {
                var sum = 0f
                for (j in 0 until W) {
                    val diff = x[j] - x[j + tau]
                    sum += diff * diff
                }
                d[tau] = sum
            }
            // Cumulative mean normalized difference.
            d[0] = 1f
            var running = 0f
            for (tau in 1..TAU_MAX + 1) {
                running += d[tau]
                d[tau] = if (running > 0f) d[tau] * tau / running else 1f
            }
            var tau = -1
            var t = TAU_MIN
            while (t <= TAU_MAX) {
                if (d[t] < 0.12f) {
                    while (t + 1 <= TAU_MAX && d[t + 1] < d[t]) t++
                    tau = t
                    break
                }
                t++
            }
            if (tau < 0) return null
            // Parabolic interpolation around the minimum.
            val s0 = d[tau - 1]
            val s1 = d[tau]
            val s2 = d[tau + 1]
            val den = s0 + s2 - 2 * s1
            val shift = if (den != 0f) 0.5f * (s0 - s2) / den else 0f
            val period = tau + shift.coerceIn(-1f, 1f)
            return SR / period
        }
    }
}
