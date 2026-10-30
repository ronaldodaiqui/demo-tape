package app.demotape.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the tuner hears: nearest note, how far off it is, and the frequency. */
data class TunerReading(val note: String, val octave: Int, val cents: Float, val hz: Float)

/**
 * Chromatic tuner. Listens to the mic through [AudioEngine.tap]; a [PitchTracker] turns the
 * sound into a steady reading about 25 times a second.
 */
class Tuner(private val engine: AudioEngine) {
    val reading = MutableStateFlow<TunerReading?>(null)

    @Volatile
    var a4 = 440f

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        val tracker = PitchTracker()
        engine.tap = { b, n -> tracker.push(b, n) }
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(40)
                tracker.a4 = a4
                reading.value = tracker.update(System.currentTimeMillis())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        engine.tap = null
        reading.value = null
    }
}

/**
 * Pitch detection plus everything that makes a tuner feel steady:
 *  - YIN finds the pitch. Low notes get a longer listening window (a low E needs more cycles
 *    to measure well), and the exact frequency is refined between samples.
 *  - Right after a pick or pluck the string is briefly sharp and noisy, so readings during the
 *    attack are skipped and the tuner follows the ringing note instead.
 *  - Readings are smoothed (median, then a gentle glide), and the note name only changes once
 *    the pitch is clearly closer to another note, so the needle and name don't flicker.
 */
class PitchTracker {
    @Volatile
    var a4 = 440f

    private val ring = ShortArray(N)
    private var written = 0L

    // smoothing state
    private val recent = ArrayDeque<Double>() // recent raw readings, in semitones (MIDI)
    private var smooth = Double.NaN
    private var shownNote = Int.MIN_VALUE
    private var lastHeard = 0L
    private var attackUntil = 0L
    private var lastRms = 0.0

    private val x = FloatArray(N)

    @Synchronized
    fun push(b: ShortArray, n: Int) {
        for (i in 0 until n) ring[((written + i) % N).toInt()] = b[i]
        written += n
    }

    /** The reading to show now, or null for "nothing to tune". */
    fun update(now: Long): TunerReading? {
        synchronized(this) {
            if (written < N) return null
            val start = written % N
            for (i in 0 until N) x[i] = ring[((start + i) % N).toInt()] / 32768f
        }
        // A sudden jump in level is a new pluck: skip the attack.
        val rms = rms(x, N - ATTACK_WIN, ATTACK_WIN)
        if (rms > lastRms * 2.0 && rms > SILENCE * 2) attackUntil = now + ATTACK_MS
        lastRms = rms

        val hz = if (now < attackUntil) null else detect(x)
        if (hz == null) {
            if (now - lastHeard > HOLD_MS) {
                reset()
                return null
            }
            return current()
        }
        lastHeard = now
        val midi = 69.0 + 12.0 * ln(hz / a4.toDouble()) / ln(2.0)

        // A different note (more than about a third of a semitone away): start fresh.
        if (!smooth.isNaN() && abs(midi - smooth) > 0.35) {
            recent.clear()
            smooth = Double.NaN
        }
        recent.addLast(midi)
        while (recent.size > 5) recent.removeFirst()
        val med = recent.sorted()[recent.size / 2]
        smooth = if (smooth.isNaN()) med else smooth + (med - smooth) * 0.35

        // Note name with a little hysteresis: switch only past 60 cents.
        val nearest = smooth.roundToInt()
        if (shownNote == Int.MIN_VALUE || abs(smooth - shownNote) > 0.6) shownNote = nearest
        return current()
    }

    private fun current(): TunerReading? {
        if (smooth.isNaN() || shownNote == Int.MIN_VALUE) return null
        val cents = ((smooth - shownNote) * 100).toFloat()
        val hz = (a4 * 2.0.pow((smooth - 69) / 12)).toFloat()
        return TunerReading(NAMES[Math.floorMod(shownNote, 12)], shownNote / 12 - 1, cents, hz)
    }

    private fun reset() {
        recent.clear()
        smooth = Double.NaN
        shownNote = Int.MIN_VALUE
    }

    companion object {
        /** Samples kept: enough for the long window plus the lowest note's period. */
        const val N = 8192
        private const val SHORT_W = 2048
        private const val LONG_W = 4096
        private const val TAU_MIN = SR / 1500
        private const val TAU_MAX = SR / 40
        private const val SILENCE = 0.006
        private const val ATTACK_WIN = 1024
        private const val ATTACK_MS = 140L
        private const val HOLD_MS = 1200L
        private val NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

        private fun rms(x: FloatArray, from: Int, n: Int): Double {
            var e = 0.0
            for (i in from until from + n) e += x[i] * x[i]
            return sqrt(e / n)
        }

        /**
         * Fundamental in Hz of the newest sound in [x], or null if there's no clear pitch.
         * First a quick look with a short window; notes below about 160 Hz are measured again
         * with a window twice as long, which steadies the low strings.
         */
        fun detect(x: FloatArray): Float? {
            val first = yin(x, SHORT_W) ?: return null
            return if (first < 160f) yin(x, LONG_W) ?: first else first
        }

        /** YIN on the newest [w] samples (plus room for the longest period before them). */
        private fun yin(x: FloatArray, w: Int): Float? {
            val start = x.size - w - TAU_MAX - 2
            if (start < 0) return null
            if (rms(x, x.size - w, w) < SILENCE) return null

            val raw = FloatArray(TAU_MAX + 2)
            for (tau in 1..TAU_MAX + 1) {
                var sum = 0f
                var j = start
                val end = start + w
                while (j < end) {
                    val diff = x[j] - x[j + tau]
                    sum += diff * diff
                    j++
                }
                raw[tau] = sum
            }
            // Cumulative mean normalized difference.
            val d = FloatArray(TAU_MAX + 2)
            d[0] = 1f
            var running = 0f
            for (tau in 1..TAU_MAX + 1) {
                running += raw[tau]
                d[tau] = if (running > 0f) raw[tau] * tau / running else 1f
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
            if (tau < 2) return null
            // Refine between samples on the raw difference (sharper than the normalized one).
            val s0 = raw[tau - 1]
            val s1 = raw[tau]
            val s2 = raw[tau + 1]
            val den = s0 + s2 - 2 * s1
            val shift = if (den > 0f) (0.5f * (s0 - s2) / den).coerceIn(-1f, 1f) else 0f
            return SR / (tau + shift)
        }
    }
}
