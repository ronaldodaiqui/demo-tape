package app.demotape

import app.demotape.audio.AudioEngine
import app.demotape.audio.Calibration
import app.demotape.audio.Resampler
import app.demotape.audio.SR
import app.demotape.audio.TimePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioCoreTest {

    @Test
    fun resamplerKeepsLengthAndPitch() {
        val inRate = 44100
        val n = inRate // one second
        val inp = ShortArray(n) { (sin(2 * PI * 1000.0 * it / inRate) * 16000).toInt().toShort() }
        val r = Resampler(inRate.toDouble() / SR)
        val out = ArrayList<Short>()
        val chunk = ShortArray(960)
        val buf = ShortArray(2048)
        var i = 0
        while (i < n) {
            val k = minOf(chunk.size, n - i)
            for (j in 0 until k) chunk[j] = inp[i + j]
            val m = r.process(chunk, k, buf)
            for (j in 0 until m) out.add(buf[j])
            i += k
        }
        assertTrue("about one second at 48 kHz, got ${out.size}", abs(out.size - SR) < 4)
        // 1 kHz has 2000 zero crossings a second.
        var crossings = 0
        for (j in 1 until out.size) if ((out[j - 1] < 0) != (out[j] < 0)) crossings++
        assertTrue("pitch kept, got $crossings crossings", abs(crossings - 2000) < 6)
    }

    @Test
    fun alignmentFindsTheOffsetBetweenPlaybackAndRecording() {
        // Playback is heard 9600 frames (200 ms) later than the same moment is captured.
        val play = (0 until 40).map { k -> TimePair(k * 4800L + 9600, 1_000_000_000L + k * 100_000_000L, true) }
        val rec = (0 until 40).map { k -> TimePair(k * 4800L, 1_000_000_000L + k * 100_000_000L, true) }
        val d = AudioEngine.alignment(play, rec, playStart = 0L)!!
        assertEquals(9600L, d)
    }

    @Test
    fun syncCheckHearsKnocksAndMeasuresTheirDelay() {
        val clicks = Calibration.clicks()
        val delay = SR / 20 // knocks land 50 ms after each click
        val len = (clicks.last() + SR).toInt()
        val x = ShortArray(len)
        val rnd = java.util.Random(1)
        for (i in 0 until len) x[i] = (rnd.nextGaussian() * 30).toInt().toShort() // room noise
        for (c in clicks) {
            val at = (c + delay).toInt()
            for (j in 0 until 200) x[at + j] = (12000 * kotlin.math.exp(-j / 30.0) * (if (j % 2 == 0) 1 else -1)).toInt().toShort()
        }
        val onsets = Calibration.onsets(x, len)
        val r = Calibration.evaluate(onsets, clicks, baseDelay = 0L)
        assertTrue("expected a result, got $r", r is Calibration.Result.Ok)
        val off = (r as Calibration.Result.Ok).offset
        assertTrue("offset $off should be about $delay", abs(off - delay) < SR / 500)
    }

    @Test
    fun syncCheckRejectsSilence() {
        val clicks = Calibration.clicks()
        val len = (clicks.last() + SR).toInt()
        val x = ShortArray(len)
        val r = Calibration.evaluate(Calibration.onsets(x, len), clicks, baseDelay = 0L)
        assertTrue(r is Calibration.Result.Fail)
    }
}
