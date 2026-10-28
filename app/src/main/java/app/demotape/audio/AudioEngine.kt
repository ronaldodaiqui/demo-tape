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

    /** A song frame and the exact moment (System.nanoTime) it was heard. */
    class Anchor(val frame: Long, val nanos: Long)

    /** Latest timing anchor while playing, for drawing a smooth playhead between updates. */
    @Volatile
    var anchor: Anchor? = null
    val playing = MutableStateFlow(false)
    val inputLevel = MutableStateFlow(0f)

    @Volatile
    private var player: Player? = null

    @Volatile
    private var input: Input? = null

    // ---------------------------------------------------------------- playback

    private val RING = 512 // blocks of history, about 10 s

    inner class Player(
        private val source: Source,
        val from: Long,
        loops: Boolean,
        stopAt: Long,
        collectPairs: Boolean,
    ) : Thread("demotape-play") {
        /** Follows the engine's loop region (read every block, so it can change while playing). */
        @Volatile
        var loops: Boolean = loops

        /** Song frame to stop at, or -1 to keep going. Can change mid-play (punch-in). */
        @Volatile
        var stopAt: Long = stopAt

        @Volatile
        private var collectPairs: Boolean = collectPairs

        /** Starts collecting timing pairs from now on (punching in during playback). */
        fun startCollecting() {
            synchronized(pairs) { pairs.clear() }
            collectPairs = true
        }

        /** Punching out: playback carries on, timing pairs are no longer needed. */
        fun stopCollecting() {
            collectPairs = false
        }

        /** Frames played so far (the linear frame count being heard right now). */
        @Volatile
        var heardK = 0L

        private val segs = ArrayList<LongArray>()

        /**
         * Where playback jumped: each entry is (linear frame, song frame) and holds until the
         * next entry. The first starts at linear frame 0; each loop wrap adds one.
         */
        fun segments(): List<LongArray> = synchronized(segs) { segs.map { it.copyOf() } }

        private fun addSeg(k: Long, song: Long) = synchronized(segs) {
            if (segs.isNotEmpty() && segs.last()[0] == k) segs.removeAt(segs.size - 1)
            segs.add(longArrayOf(k, song))
        }

        @Volatile
        var stopFlag = false

        @Volatile
        var endedNaturally = false

        @Volatile
        var startNanos = 0L
        val done = CompletableDeferred<Unit>()
        private val pairs = ArrayList<TimePair>()

        fun pairs(): List<TimePair> = synchronized(pairs) { ArrayList(pairs) }

        private val looping get() = loops && loopEnd > loopStart

        // Song frame of the next frame to render, and a ring of where each written block
        // started (plus where it wrapped, if it hit the loop end), so the frame being heard
        // can be mapped back to the song even though the loop can change while playing.
        private var next = from
        private val ringStart = LongArray(RING)
        private val ringWrapAt = IntArray(RING)
        private val ringWrapTo = LongArray(RING)

        fun songFrame(k: Long): Long {
            val bi = ((k / BLOCK) % RING).toInt()
            val off = (k % BLOCK).toInt()
            val w = ringWrapAt[bi]
            return if (w in 0..off) ringWrapTo[bi] + (off - w) else ringStart[bi] + off
        }

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
            var gotReal = false

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
                    if (real) gotReal = true
                    // Until Android reports exact timing, hold at the start: the rough fallback
                    // count runs ahead of what you hear (far ahead on Bluetooth) and would jump.
                    val starting = !gotReal && now - startNanos < 700_000_000L
                    val heard = when {
                        real -> ts.framePosition + (now - ts.nanoTime) * SR / 1_000_000_000L
                        starting -> 0L
                        else -> track.playbackHeadPosition.toLong() and 0xffffffffL
                    }
                    val h = heard.coerceIn(0L, written)
                    heardK = h
                    position.value = songFrame(h)
                    anchor = when {
                        real && ts.framePosition in 0..written -> Anchor(songFrame(ts.framePosition), ts.nanoTime)
                        starting -> null
                        else -> Anchor(songFrame(h), now)
                    }

                    if (collectPairs && now - lastPair > 50_000_000L) {
                        lastPair = now
                        synchronized(pairs) {
                            // Linear frames (not song frames), so loop wraps don't disturb alignment.
                            if (real) pairs.add(TimePair(ts.framePosition, ts.nanoTime, true))
                            else pairs.add(TimePair(h, now, false))
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
            val bi = ((k / BLOCK) % RING).toInt()
            ringStart[bi] = next
            ringWrapAt[bi] = -1
            if (k == 0L) addSeg(0L, next)
            var done = 0
            while (done < BLOCK) {
                val ls = loopStart
                val le = loopEnd
                val on = loops && le - ls >= BLOCK
                if (on && next >= le) {
                    // Hit (or are past) the loop end: jump back to its start.
                    if (done == 0) ringStart[bi] = ls else {
                        ringWrapAt[bi] = done
                        ringWrapTo[bi] = ls
                    }
                    next = ls
                    addSeg(k + done, ls)
                }
                val room = if (on) le - next else Long.MAX_VALUE
                val n = minOf((BLOCK - done).toLong(), room).toInt()
                if (n == BLOCK) {
                    source.render(next, l, r, BLOCK)
                } else {
                    source.render(next, pl, pr, n)
                    System.arraycopy(pl, 0, l, done, n)
                    System.arraycopy(pr, 0, r, done, n)
                }
                next += n
                done += n
            }
        }
    }

    /** Loop region in song frames; playback that allows looping follows it live. */
    @Volatile
    var loopStart = -1L
        private set

    @Volatile
    var loopEnd = -1L
        private set

    fun setLoop(start: Long, end: Long) {
        loopStart = start
        loopEnd = end
    }

    suspend fun play(
        source: Source,
        from: Long,
        loops: Boolean = false,
        stopAt: Long = -1,
        collectPairs: Boolean = false,
    ): Player {
        stopPlayback()
        anchor = null
        val p = Player(source, from, loops, stopAt, collectPairs)
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
            // 48 kHz first; Android only guarantees 44.1 kHz, so fall back to that and resample.
            var rec: AudioRecord? = null
            var inRate = SR
            for (rate in intArrayOf(SR, 44100)) {
                val r = try {
                    buildRecorder(rate)
                } catch (e: Exception) {
                    null
                }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    rec = r
                    inRate = rate
                    break
                }
                r?.release()
            }
            if (rec == null) {
                failed = "microphone unavailable"
                ready.complete(Unit)
                return
            }
            val resampler = if (inRate != SR) Resampler(inRate.toDouble() / SR) else null
            try {
                rec.startRecording()
                val raw = ShortArray(BLOCK)
                val conv = ShortArray(BLOCK * 2)
                val ts = AudioTimestamp()
                var lastPair = 0L
                var hold = 0f
                while (running) {
                    val got = rec.read(raw, 0, BLOCK)
                    if (got < 0) {
                        failed = "read error $got"
                        break
                    }
                    if (got == 0) continue
                    val buf: ShortArray
                    val n: Int
                    if (resampler != null) {
                        n = resampler.process(raw, got, conv)
                        buf = conv
                    } else {
                        n = got
                        buf = raw
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
                                // Recorder frames are at the mic's rate; count them at 48 kHz.
                                pairs.add(TimePair(ts.framePosition * SR / inRate, ts.nanoTime, true))
                            } else {
                                pairs.add(TimePair(framesRead, now, false))
                            }
                        }
                    }
                    tap?.invoke(buf, n)
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
        private fun buildRecorder(rate: Int): AudioRecord {
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) error("rate $rate not supported")
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
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf * 4, rate / 4 * 2))
                .build()
            // Always the phone's own mic. A Bluetooth mic would drop the headphones
            // into call mode and ruin both recording and playback quality.
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { rec.setPreferredDevice(it) }
            return rec
        }
    }

    /** Gets every buffer the mic delivers (on the recording thread). Used by the tuner. */
    @Volatile
    var tap: ((ShortArray, Int) -> Unit)? = null

    /** Why the mic stopped working, if it did. */
    val inputFailure: String? get() = input?.let { if (it.failed != null) it.failed else if (!it.isAlive && it.running) "microphone stopped" else null }

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
    suspend fun startTake(source: Source, from: Long, sink: RecordSink, loops: Boolean = false): Player? {
        val i = input ?: return null
        i.attach(sink)
        return play(source, from, loops = loops, collectPairs = true)
    }

    /**
     * Starts capturing into [sink] while the current playback keeps running (punch-in).
     * Returns the running player, or null if nothing is playing.
     */
    fun punchIn(sink: RecordSink): Player? {
        val p = player ?: return null
        if (!p.isAlive || p.stopFlag) return null
        val i = input ?: return null
        p.startCollecting()
        p.stopAt = -1
        i.attach(sink)
        return p
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
