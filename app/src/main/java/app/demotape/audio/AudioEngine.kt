package app.demotape.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max

/** A moment where a known frame was heard (playback) or captured (recording). */
class TimePair(val frame: Long, val nanos: Long, val real: Boolean)

/**
 * Plays a [Source] through AudioTrack and records the phone's built-in mic through
 * AudioRecord. Overdubs are lined up afterwards from the two streams' timestamps,
 * so buffer sizes and Bluetooth delay don't need to be small, only measured.
 */
class AudioEngine(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)

    /** Song frame currently being heard. */
    val position = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val inputLevel = MutableStateFlow(0f)

    @Volatile
    private var player: Player? = null

    @Volatile
    private var input: Input? = null

    // ---------------------------------------------------------------- playback

    inner class Player(
        private val source: Source,
        private val from: Long,
        private val loopStart: Long,
        private val loopEnd: Long,
        private val stopAt: Long,
        private val collectPairs: Boolean,
    ) : Thread("demotape-play") {
        @Volatile
        var stopFlag = false

        @Volatile
        var endedNaturally = false

        @Volatile
        var startNanos = 0L
        val done = CompletableDeferred<Unit>()
        private val pairs = ArrayList<TimePair>()

        fun pairs(): List<TimePair> = synchronized(pairs) { ArrayList(pairs) }

        private val looping get() = loopEnd > loopStart

        fun songFrame(k: Long): Long =
            if (looping) loopStart + Math.floorMod(from - loopStart + k, loopEnd - loopStart)
            else from + k

        override fun run() {
            try {
                body()
            } catch (t: Throwable) {
                Log.e("DemoTape", "playback failed", t)
            } finally {
                if (player === this) playing.value = false
                done.complete(Unit)
            }
        }

        private fun body() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuf = AudioTrack.getMinBufferSize(
                SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, BLOCK * 2 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val bl = FloatArray(BLOCK)
            val br = FloatArray(BLOCK)
            val pl = FloatArray(BLOCK)
            val pr = FloatArray(BLOCK)
            val st = FloatArray(BLOCK * 2)
            val ts = AudioTimestamp()
            var written = 0L
            var lastPair = 0L

            track.play()
            startNanos = System.nanoTime()
            try {
                while (!stopFlag) {
                    renderBlock(written, bl, br, pl, pr)
                    for (i in 0 until BLOCK) {
                        st[2 * i] = softClip(bl[i])
                        st[2 * i + 1] = softClip(br[i])
                    }
                    var off = 0
                    while (off < st.size && !stopFlag) {
                        val w = track.write(st, off, st.size - off, AudioTrack.WRITE_BLOCKING)
                        if (w < 0) {
                            stopFlag = true
                            break
                        }
                        off += w
                    }
                    written += BLOCK

                    val now = System.nanoTime()
                    val real = track.getTimestamp(ts)
                    val heard = if (real) {
                        ts.framePosition + (now - ts.nanoTime) * SR / 1_000_000_000L
                    } else {
                        track.playbackHeadPosition.toLong() and 0xffffffffL
                    }
                    val h = heard.coerceIn(0L, written)
                    position.value = songFrame(h)

                    if (collectPairs && now - lastPair > 50_000_000L) {
                        lastPair = now
                        synchronized(pairs) {
                            if (real) pairs.add(TimePair(songFrame(ts.framePosition), ts.nanoTime, true))
                            else pairs.add(TimePair(songFrame(h), now, false))
                        }
                    }
                    if (stopAt >= 0 && !looping && songFrame(h) >= stopAt) {
                        endedNaturally = true
                        break
                    }
                }
            } finally {
                try {
                    track.pause()
                    track.flush()
                    track.stop()
                } catch (_: Exception) {
                }
                track.release()
            }
        }

        private fun renderBlock(k: Long, l: FloatArray, r: FloatArray, pl: FloatArray, pr: FloatArray) {
            if (!looping) {
                source.render(from + k, l, r, BLOCK)
                return
            }
            var done = 0
            while (done < BLOCK) {
                val sf = songFrame(k + done)
                val n = minOf((BLOCK - done).toLong(), loopEnd - sf).toInt()
                source.render(sf, pl, pr, n)
                System.arraycopy(pl, 0, l, done, n)
                System.arraycopy(pr, 0, r, done, n)
                done += n
            }
        }
    }

    suspend fun play(
        source: Source,
        from: Long,
        loopStart: Long = -1,
        loopEnd: Long = -1,
        stopAt: Long = -1,
        collectPairs: Boolean = false,
    ): Player {
        stopPlayback()
        val p = Player(source, from, loopStart, loopEnd, stopAt, collectPairs)
        player = p
        position.value = from
        playing.value = true
        p.start()
        return p
    }

    suspend fun stopPlayback() {
        val p = player ?: return
        p.stopFlag = true
        withContext(Dispatchers.IO) { p.join(1500) }
        if (player === p) player = null
        playing.value = false
    }

    // ---------------------------------------------------------------- input

    inner class Input : Thread("demotape-rec") {
        @Volatile
        var running = true

        @Volatile
        var failed: String? = null
        val ready = CompletableDeferred<Unit>()
        private val lock = Object()
        private var sink: RecordSink? = null
        private val pairs = ArrayList<TimePair>()
        private var framesRead = 0L

        fun attach(s: RecordSink) = synchronized(lock) {
            pairs.clear()
            sink = s
        }

        fun detach(): List<TimePair> = synchronized(lock) {
            sink?.finish()
            sink = null
            ArrayList(pairs)
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rec = try {
                buildRecorder()
            } catch (e: Exception) {
                failed = e.message ?: "microphone unavailable"
                ready.complete(Unit)
                return
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                failed = "microphone unavailable"
                rec.release()
                ready.complete(Unit)
                return
            }
            try {
                rec.startRecording()
                val buf = ShortArray(BLOCK)
                val ts = AudioTimestamp()
                var lastPair = 0L
                var hold = 0f
                while (running) {
                    val n = rec.read(buf, 0, BLOCK)
                    if (n < 0) {
                        failed = "read error $n"
                        break
                    }
                    if (n == 0) continue
                    val now = System.nanoTime()
                    var pk = 0
                    for (i in 0 until n) {
                        val a = abs(buf[i].toInt())
                        if (a > pk) pk = a
                    }
                    hold = max(pk / 32768f, hold * 0.88f)
                    inputLevel.value = hold

                    synchronized(lock) {
                        val s = sink
                        if (s != null) {
                            if (s.startFrame < 0) s.startFrame = framesRead
                            s.write(buf, n)
                        }
                        framesRead += n
                        if (s != null && now - lastPair > 50_000_000L) {
                            lastPair = now
                            if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                pairs.add(TimePair(ts.framePosition, ts.nanoTime, true))
                            } else {
                                pairs.add(TimePair(framesRead, now, false))
                            }
                        }
                    }
                    if (!ready.isCompleted) ready.complete(Unit)
                }
            } catch (t: Throwable) {
                Log.e("DemoTape", "recording failed", t)
                failed = t.message ?: "recording failed"
            } finally {
                synchronized(lock) {
                    sink?.finish()
                    sink = null
                }
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
                inputLevel.value = 0f
                if (!ready.isCompleted) ready.complete(Unit)
            }
        }

        @SuppressLint("MissingPermission")
        private fun buildRecorder(): AudioRecord {
            val minBuf = AudioRecord.getMinBufferSize(
                SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val unprocessed =
                am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            // Unprocessed (or voice recognition) avoids the automatic gain that makes
            // music pump on the default MIC source.
            val src = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val rec = AudioRecord.Builder()
                .setAudioSource(src)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf * 4, SR / 4 * 2))
                .build()
            // Always the phone's own mic. A Bluetooth mic would drop the headphones
            // into call mode and ruin both recording and playback quality.
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { rec.setPreferredDevice(it) }
            return rec
        }
    }

    val inputOpen: Boolean get() = input?.let { it.isAlive && it.running } == true

    fun openInput() {
        if (inputOpen) return
        input = Input().also { it.start() }
    }

    fun closeInput() {
        input?.running = false
        input = null
        inputLevel.value = 0f
    }

    /** Waits until the mic delivers audio. Returns an error message, or null when ready. */
    suspend fun awaitInput(): String? {
        val i = input ?: return "microphone is closed"
        withTimeoutOrNull(2500) { i.ready.await() } ?: return "microphone didn't start"
        return i.failed
    }

    // ---------------------------------------------------------------- takes

    /** Starts playing [source] from [from] while capturing the mic into [sink]. */
    suspend fun startTake(source: Source, from: Long, sink: RecordSink): Player? {
        val i = input ?: return null
        i.attach(sink)
        return play(source, from, collectPairs = true)
    }

    /** Stops capture. Returns the recorder's timing pairs. */
    fun detachSink(): List<TimePair> = input?.detach() ?: emptyList()

    /** Runs the tap-along sync check. Input must already be open. */
    suspend fun calibrate(clicks: LongArray): Calibration.Result {
        val i = input ?: return Calibration.Result.Fail("The microphone isn't open.")
        val endAt = clicks.last() + SR * 7L / 10
        val sink = MemorySink((endAt + SR * 2).toInt())
        i.attach(sink)
        val p = play(ClickSource(clicks), 0, stopAt = endAt, collectPairs = true)
        p.done.await()
        val recPairs = i.detach()
        if (player === p) player = null
        playing.value = false
        if (sink.startFrame < 0 || sink.frames < SR) {
            return Calibration.Result.Fail("The microphone didn't pick anything up.")
        }
        val d = alignment(p.pairs(), recPairs, p.startNanos)
            ?: return Calibration.Result.Fail("Couldn't read the audio timing. Try again.")
        val onsets = Calibration.onsets(sink.data, sink.frames.toInt())
        val songOnsets = LongArray(onsets.size) { onsets[it] + sink.startFrame + d }
        // Round trip if nothing were compensated: from(0) - startFrame - d.
        return Calibration.evaluate(songOnsets, clicks, baseDelay = -sink.startFrame - d)
    }

    companion object {
        /**
         * Offset D such that recorded frame r lands at song frame r + D, from playback
         * pairs (song frame heard at time t) and record pairs (frame captured at time t).
         */
        fun alignment(play: List<TimePair>, rec: List<TimePair>, playStart: Long): Long? {
            val settled = play.filter { it.nanos > playStart + 300_000_000L }
            val pAll = settled.ifEmpty { play }
            val pp = pAll.filter { it.real }.ifEmpty { pAll }
            val rp = rec.filter { it.real }.ifEmpty { rec }
            if (pp.isEmpty() || rp.isEmpty()) return null
            val ds = ArrayList<Long>(rp.size)
            for (r in rp) {
                val p = pp.minByOrNull { abs(it.nanos - r.nanos) } ?: continue
                ds.add(p.frame - r.frame + (r.nanos - p.nanos) * SR / 1_000_000_000L)
            }
            if (ds.isEmpty()) return null
            ds.sort()
            return ds[ds.size / 2]
        }
    }
}
