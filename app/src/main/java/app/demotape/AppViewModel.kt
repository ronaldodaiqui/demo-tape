package app.demotape

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.demotape.audio.AudioEngine
import app.demotape.audio.Calibration
import app.demotape.audio.ClipAudio
import app.demotape.audio.Exporter
import app.demotape.audio.Drums
import app.demotape.audio.FileSink
import app.demotape.audio.Metronome
import app.demotape.audio.SR
import app.demotape.audio.SongSource
import app.demotape.audio.Tuner
import app.demotape.data.Clip
import app.demotape.data.OutputDevice
import app.demotape.data.OutputKind
import app.demotape.data.Outputs
import app.demotape.data.Song
import app.demotape.data.SongRepository
import app.demotape.data.SyncInfo
import app.demotape.data.SyncStore
import app.demotape.data.TRACKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Waveform of the take being recorded right now. */
class LiveTake(val track: Int, val from: Long, val peaks: ByteArray)

/** The take being lined up by hand. [atOpen] is its nudge when the panel opened. */
data class NudgeState(val clipId: String, val track: Int, val atOpen: Long, val remember: Boolean)

sealed class CalState {
    abstract val thenRecord: Int?

    data class Intro(override val thenRecord: Int?) : CalState()
    data class Running(val clicks: LongArray, override val thenRecord: Int?) : CalState()
    data class Done(val delayMs: Int, override val thenRecord: Int?) : CalState()
    data class Failed(val reason: String, override val thenRecord: Int?) : CalState()
}

internal class TakeCtx(
    val id: String,
    val track: Int,
    val from: Long,
    val playFrom: Long,
    /** Linear playback frame where the take begins (after count-in, or the punch point). */
    val kRec: Long,
    /** Linear playback frame when capture started (0, or the punch point). */
    val kCap: Long,
    val sink: FileSink,
    val player: AudioEngine.Player,
    val device: String,
) {
    /** Video is being filmed alongside this take. */
    var filming = false
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val repo = SongRepository(File(app.filesDir, "songs"))
    private val audio = ClipAudio(repo)
    val engine = AudioEngine(app)
    private val outputs = Outputs(app)
    private val sync = SyncStore(app)

    val songs = MutableStateFlow<List<Song>>(emptyList())
    val song = MutableStateFlow<Song?>(null)
    val peaks = mutableStateMapOf<String, ByteArray>()
    val armed = MutableStateFlow(-1)
    val recordingTrack = MutableStateFlow(-1)
    val playhead = MutableStateFlow(0L)
    val lastTake = MutableStateFlow<String?>(null)
    val liveTake = MutableStateFlow<LiveTake?>(null)
    val nudge = MutableStateFlow<NudgeState?>(null)
    val syncPrompt = MutableStateFlow<OutputDevice?>(null)
    val cal = MutableStateFlow<CalState?>(null)
    val busy = MutableStateFlow<String?>(null)

    val tuner = Tuner(engine).also {
        it.a4 = app.getSharedPreferences("tuner", 0).getFloat("a4", 440f)
    }
    val tunerOpen = MutableStateFlow(false)

    val video = VideoRecorder(app)
    val videoMode = MutableStateFlow(false)
    val tunerA4 = MutableStateFlow(tuner.a4)

    /** While counting in before a take: the song frame where recording begins. */
    val countInTo = MutableStateFlow<Long?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val output: StateFlow<OutputDevice> = outputs.current
    val currentSync: StateFlow<SyncInfo?> =
        combine(outputs.current, sync.version) { d, _ -> sync.get(d.key) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, sync.get(outputs.current.value.key))

    private val transport = Mutex()
    private var source: SongSource? = null
    private var take: TakeCtx? = null
    private var saveJob: Job? = null
    private var liveJob: Job? = null
    private var endWatch: Job? = null
    private var lastPlayFrom = 0L

    /** Raw samples of a take, for drawing detailed waveforms when zoomed in. */
    fun samples(clipId: String): java.nio.ShortBuffer? {
        val s = song.value ?: return null
        return audio.get(s.id, clipId)
    }

    init {
        viewModelScope.launch { songs.value = withContext(Dispatchers.IO) { repo.list() } }
    }

    private fun msg(s: String) {
        messages.tryEmit(s)
    }

    private fun launchT(block: suspend () -> Unit) {
        viewModelScope.launch { transport.withLock { block() } }
    }

    private fun updateSong(save: Boolean = true, f: (Song) -> Song) {
        val s = song.value ?: return
        val n = f(s).copy(modified = System.currentTimeMillis())
        song.value = n
        source?.song = n
        syncLoop(n)
        if (save) scheduleSave(n)
    }

    private fun syncLoop(s: Song) {
        if (s.loopOn && s.hasLoop) engine.setLoop(s.loopStart, s.loopEnd) else engine.setLoop(-1L, -1L)
    }

    private fun scheduleSave(s: Song) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            repo.save(s)
        }
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)

    // ------------------------------------------------------------ songs

    fun newSong() = viewModelScope.launch {
        val base = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        val names = songs.value.map { it.name }.toSet()
        var name = base
        var i = 2
        while (name in names) name = "$base (${i++})"
        val s = withContext(Dispatchers.IO) { repo.create(name) }
        openSongNow(s.id)
    }

    fun openSong(id: String) = viewModelScope.launch { openSongNow(id) }

    private suspend fun openSongNow(id: String) {
        val loaded = withContext(Dispatchers.IO) {
            val s = repo.load(id) ?: return@withContext null
            val pk = HashMap<String, ByteArray>()
            for (c in s.clips) {
                if (pk.containsKey(c.fileId)) continue
                val buf = audio.get(s.id, c.fileId)
                val p = repo.loadPeaks(s.id, c.fileId)
                    ?: buf?.let { b ->
                        // Takes from before the new waveform style: analyze them once.
                        app.demotape.audio.PeakBuilder.fromSamples(b).also { repo.savePeaks(s.id, c.fileId, it) }
                    }
                if (p != null) pk[c.fileId] = p
            }
            s to pk
        } ?: return
        peaks.clear()
        peaks.putAll(loaded.second)
        playhead.value = 0L
        // Start ready to record on the first empty track (track 1 for a new song).
        armed.value = (0 until TRACKS).firstOrNull { !loaded.first.hasAudio(it) } ?: -1
        if (armed.value >= 0 && micAllowed()) engine.openInput()
        lastTake.value = null
        nudge.value = null
        source = SongSource(loaded.first, audio).also { it.metronome = true }
        syncLoop(loaded.first)
        song.value = loaded.first
    }

    fun closeSong() = launchT {
        if (take != null) stopTakeLocked()
        videoMode.value = false
        video.unbind()
        nudge.value = null
        engine.stopPlayback()
        engine.closeInput()
        armed.value = -1
        song.value?.let { s ->
            saveJob?.cancel()
            withContext(Dispatchers.IO) { repo.save(s) }
        }
        song.value = null
        source = null
        audio.clear()
        peaks.clear()
        songs.value = withContext(Dispatchers.IO) { repo.list() }
    }

    fun renameSong(id: String, name: String) = viewModelScope.launch {
        val n = name.trim().ifEmpty { return@launch }
        if (song.value?.id == id) {
            updateSong { it.copy(name = n) }
        } else {
            withContext(Dispatchers.IO) { repo.load(id)?.let { repo.save(it.copy(name = n)) } }
        }
        songs.value = withContext(Dispatchers.IO) { repo.list() }.map { if (it.id == id) it.copy(name = n) else it }
    }

    fun deleteSong(id: String) = launchT {
        if (song.value?.id == id) {
            engine.stopPlayback()
            engine.closeInput()
            saveJob?.cancel()
            song.value = null
            source = null
            audio.clear()
        }
        withContext(Dispatchers.IO) { repo.delete(id) }
        songs.value = withContext(Dispatchers.IO) { repo.list() }
    }

    // ------------------------------------------------------------ transport

    fun togglePlay() = launchT {
        if (take != null) {
            stopTakeLocked()
            return@launchT
        }
        if (engine.playing.value) {
            engine.stopPlayback()
            // While lining up, stopping returns to where play started so the same bit can be replayed.
            playhead.value = if (nudge.value != null) lastPlayFrom else engine.position.value
        } else {
            startPlaybackLocked(playhead.value)
        }
    }

    private suspend fun startPlaybackLocked(fromIn: Long) {
        val s = song.value ?: return
        val src = source ?: return
        val len = s.length
        if (len <= 0 && !s.drumsOn) {
            msg("Nothing recorded yet")
            return
        }
        val looping = s.loopOn && s.hasLoop
        val from = when {
            looping && fromIn >= s.loopEnd -> s.loopStart
            !looping && fromIn >= len - SR / 10 -> 0L
            else -> fromIn.coerceAtLeast(0L)
        }
        src.silentTrack = -1
        playhead.value = from
        lastPlayFrom = from
        // With only drums so far, keep playing until stopped.
        val p = engine.play(src, from, loops = true, stopAt = if (len > 0) len else -1L)
        endWatch?.cancel()
        endWatch = viewModelScope.launch {
            p.done.await()
            if (p.endedNaturally) playhead.value = from
        }
    }

    fun seek(frame: Long) = launchT {
        if (take != null) return@launchT
        val f = frame.coerceAtLeast(0L)
        if (engine.playing.value) {
            engine.stopPlayback()
            playhead.value = f
            startPlaybackLocked(f)
        } else {
            playhead.value = f
        }
    }

    fun toStart() = seek(0L)

    // ------------------------------------------------------------ recording

    private fun micAllowed() = androidx.core.content.ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Opens the mic for the armed track once permission is there. */
    fun openArmedInput() {
        if (armed.value >= 0 && song.value != null && micAllowed()) engine.openInput()
    }

    fun toggleArm(t: Int) {
        if (take != null || nudge.value != null || cal.value != null) return
        if (armed.value == t) {
            armed.value = -1
            engine.closeInput()
        } else {
            armed.value = t
            engine.openInput()
        }
    }

    fun recordPressed() = launchT {
        if (take != null) {
            stopTakeLocked()
            return@launchT
        }
        if (nudge.value != null) closeNudgeLocked(nudge.value?.remember == true)
        val s = song.value ?: return@launchT
        var t = armed.value
        if (t < 0) {
            t = (0 until TRACKS).firstOrNull { !s.hasAudio(it) } ?: run {
                msg("Tap a track number to choose which track to record on")
                return@launchT
            }
            armed.value = t
            engine.openInput()
        }
        val dev = outputs.current.value
        if (dev.kind == OutputKind.BLUETOOTH && sync.get(dev.key) == null) {
            syncPrompt.value = dev
            return@launchT
        }
        startTakeLocked(t)
    }

    fun skipSync() {
        val dev = syncPrompt.value ?: return
        syncPrompt.value = null
        sync.put(dev.key, SyncInfo(0L, 0, synced = false))
        val t = armed.value
        if (t >= 0) launchT { startTakeLocked(t) }
    }

    fun syncFromPrompt() {
        syncPrompt.value = null
        cal.value = CalState.Intro(armed.value.takeIf { it >= 0 })
    }

    fun dismissSyncPrompt() {
        syncPrompt.value = null
    }

    private suspend fun startTakeLocked(t: Int) {
        val s = song.value ?: return
        val src = source ?: return
        nudge.value = null
        lastTake.value = null
        engine.openInput()
        engine.awaitInput()?.let {
            msg("Couldn't start the microphone: $it")
            return
        }
        val id = newId()
        val file = repo.clipFile(s.id, id).also { it.parentFile?.mkdirs() }
        val sink = FileSink(file)

        // Already playing: punch in right where playback is, without stopping.
        if (engine.playing.value) {
            val from = engine.position.value.coerceAtLeast(0L)
            src.silentTrack = t
            src.countInUntil = Long.MIN_VALUE
            val p = engine.punchIn(sink)
            if (p != null) {
                endWatch?.cancel()
                val k = p.heardK
                take = TakeCtx(id, t, from, p.from, k, k, sink, p, outputs.current.value.key)
                countInTo.value = null
                armed.value = t
                recordingTrack.value = t
                startFilming(take!!)
                startLive(take!!)
                return
            }
            src.silentTrack = -1
        }

        engine.stopPlayback()
        src.silentTrack = t
        val looping = s.loopOn && s.hasLoop
        val from = playhead.value.coerceAtLeast(0L).let { if (looping && it >= s.loopEnd) s.loopStart else it }
        // With the metronome on, playback starts a bar early for a count-in.
        val bar = if (s.click && s.countIn)
            Math.round(Metronome.beatLength(s.bpm) * s.beatsPerBar) else 0L
        val playFrom = from - bar
        src.countInUntil = if (bar > 0) from else Long.MIN_VALUE
        val dev = outputs.current.value
        val p = engine.startTake(src, playFrom, sink, loops = looping) ?: run {
            sink.finish()
            file.delete()
            src.silentTrack = -1
            src.countInUntil = Long.MIN_VALUE
            msg("Couldn't start recording")
            return
        }
        take = TakeCtx(id, t, from, playFrom, from - playFrom, 0L, sink, p, dev.key)
        countInTo.value = if (bar > 0) from else null
        armed.value = t
        recordingTrack.value = t
        startFilming(take!!)
        startLive(take!!)
    }

    private fun startFilming(tk: TakeCtx) {
        val s = song.value ?: return
        if (videoMode.value) tk.filming = video.start(repo.videoFile(s.id, tk.id))
    }

    fun setVideoMode(on: Boolean) {
        if (take != null) return
        videoMode.value = on
        if (!on) video.unbind()
    }

    fun videoFile(c: Clip): File = repo.videoFile(song.value?.id ?: "", c.fileId)

    /** Shows the waveform being recorded; when looping, just the current pass. */
    private fun startLive(tk: TakeCtx) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            while (isActive) {
                val all = tk.sink.peaks.snapshot()
                val segs = tk.player.segments()
                val hk = tk.player.heardK
                val cur = segs.lastOrNull { it[0] <= hk } ?: longArrayOf(0L, tk.playFrom)
                val begin = maxOf(cur[0], tk.kRec)
                val offset = (((begin - tk.kCap) / app.demotape.audio.PEAK_HOP).toInt() * 2).coerceIn(0, all.size)
                liveTake.value = LiveTake(tk.track, cur[1] + (begin - cur[0]), all.copyOfRange(offset, all.size))
                delay(100)
            }
        }
    }

    fun stopRecording() = launchT { stopTakeLocked() }

    private suspend fun stopTakeLocked() {
        val tk = take ?: return
        take = null
        liveJob?.cancel()
        val recPairs = engine.detachSink()
        engine.stopPlayback()
        val vidStart = if (tk.filming) video.stop() else null
        val playPairs = tk.player.pairs()
        recordingTrack.value = -1
        liveTake.value = null
        countInTo.value = null
        source?.silentTrack = -1
        source?.countInUntil = Long.MIN_VALUE
        val s = song.value ?: return
        val discard: suspend () -> Unit = {
            withContext(Dispatchers.IO) { repo.deleteClip(s.id, tk.id) }
            msg("Take was too short")
            playhead.value = tk.from
        }
        if (tk.sink.startFrame < 0) {
            discard()
            return
        }
        // Linear playback frame k for recorded frame r: k = r + dk. The player's segments map
        // linear frames to song frames (one segment per pass when looping).
        val dk = AudioEngine.alignment(playPairs, recPairs, tk.player.startNanos)
            ?: (0L - tk.sink.startFrame)
        val extra = sync.get(tk.device)?.offset ?: 0L
        val rFirst = tk.sink.startFrame
        val kFirst = rFirst + dk - extra
        val kEnd = kFirst + tk.sink.frames
        val segs = tk.player.segments().ifEmpty { listOf(longArrayOf(0L, tk.playFrom)) }
        val now = System.currentTimeMillis()
        data class Piece(val songStart: Long, val length: Long, val skip: Long, val segLen: Long, val k: Long)
        val pieces = ArrayList<Piece>()
        for (i in segs.indices) {
            val segStart = segs[i][0]
            val segEnd = if (i + 1 < segs.size) segs[i + 1][0] else Long.MAX_VALUE
            // The take begins exactly where record was pressed; count-in and pre-roll audio is
            // skipped, so punching in never eats into what came before.
            val ks = maxOf(segStart, tk.kRec, kFirst)
            val ke = minOf(segEnd, kEnd)
            if (ke - ks < SR / 4) continue
            pieces += Piece(segs[i][1] + (ks - segStart), ke - ks, ks - kFirst, segEnd - segStart, ks)
        }
        if (pieces.isEmpty()) {
            discard()
            return
        }
        // Video: the playback frame being heard when its first frame was filmed.
        val kVid: Long? = vidStart?.let { t ->
            val pp = playPairs.filter { it.real }.ifEmpty { playPairs }
            pp.minByOrNull { abs(it.nanos - t) }?.let { p -> p.frame + (t - p.nanos) * SR / 1_000_000_000L }
        }
        if (tk.filming && kVid == null) withContext(Dispatchers.IO) { repo.videoFile(s.id, tk.id).delete() }
        val pk = tk.sink.peaks.snapshot()
        withContext(Dispatchers.IO) {
            repo.savePeaks(s.id, tk.id, pk)
            audio.get(s.id, tk.id)
        }
        peaks[tk.id] = pk

        val clips: List<Clip>
        if (pieces.size == 1) {
            val pc = pieces[0]
            clips = listOf(
                Clip(
                    tk.id, tk.track, pc.songStart, 0L, pc.length, now, tk.device, pc.skip,
                    video = kVid != null, videoOffset = if (kVid != null) pc.k - kVid else 0L,
                )
            )
        } else {
            // Loop passes: keep them all, show the newest complete one.
            val shown = pieces.indices.lastOrNull { pieces[it].length >= pieces[it].segLen * 9 / 10 }
                ?: pieces.lastIndex
            clips = pieces.mapIndexed { i, pc ->
                Clip(
                    id = newId(), track = tk.track, start = pc.songStart, nudge = 0L, length = pc.length,
                    created = now, device = tk.device, skip = pc.skip,
                    file = tk.id, group = tk.id, pass = i, hidden = i != shown,
                    video = kVid != null, videoOffset = if (kVid != null) pc.k - kVid else 0L,
                )
            }
        }
        // Offer "Line up" on the song's first overdub (after that the headphones are dialed in),
        // and always after a loop recording so a different pass can be picked right away.
        val overdub = s.clips.any { it.track != tk.track }
        val earlierOverdub = s.clips.any { c -> s.clips.any { o -> o.track != c.track && o.created < c.created } }
        updateSong { it.copy(clips = it.clips + clips) }
        val visible = clips.first { !it.hidden }
        if (clips.size > 1 || (overdub && !earlierOverdub)) lastTake.value = visible.id
        playhead.value = tk.from
    }

    fun undoLastTake() = launchT {
        if (take != null) return@launchT
        val s = song.value ?: return@launchT
        val c = s.clips.maxByOrNull { it.created } ?: return@launchT
        removeClipsLocked(if (c.group.isEmpty()) listOf(c) else s.passes(c.group))
        msg("Removed the last take on track ${c.track + 1}")
    }

    fun deleteClip(id: String) = launchT {
        val c = song.value?.clips?.find { it.id == id } ?: return@launchT
        removeClipsLocked(listOf(c))
    }

    fun clearTrack(t: Int) = launchT {
        val cs = song.value?.clips?.filter { it.track == t } ?: return@launchT
        if (cs.isNotEmpty()) removeClipsLocked(cs)
    }

    private suspend fun removeClipsLocked(cs: List<Clip>) {
        val ids = cs.map { it.id }.toSet()
        if (nudge.value?.clipId in ids) closeNudgeLocked(false)
        updateSong { s ->
            var left = s.clips.filter { c -> c.id !in ids }
            // If the shown pass of a loop recording was deleted, show the newest remaining one.
            for (g in cs.map { it.group }.filter { it.isNotEmpty() }.toSet()) {
                val rest = left.filter { it.group == g }
                if (rest.isNotEmpty() && rest.all { it.hidden }) {
                    val keep = rest.maxBy { it.pass }.id
                    left = left.map { if (it.id == keep) it.copy(hidden = false) else it }
                }
            }
            s.copy(clips = left)
        }
        val sid = song.value?.id ?: return
        val remaining = song.value?.clips.orEmpty()
        val files = cs.map { it.fileId }.toSet().filter { f -> remaining.none { it.fileId == f } }
        for (f in files) {
            peaks.remove(f)
            audio.evict(f)
        }
        if (lastTake.value in ids) lastTake.value = null
        withContext(Dispatchers.IO) { files.forEach { repo.deleteClip(sid, it) } }
    }

    /** Sets a take's volume (all passes of a loop recording together). */
    fun setTakeGain(id: String, db: Float) {
        val c = song.value?.clips?.find { it.id == id } ?: return
        val v = db.coerceIn(-12f, 24f)
        updateSong { s ->
            s.copy(clips = s.clips.map {
                if (it.id == id || (c.group.isNotEmpty() && it.group == c.group)) it.copy(gainDb = v) else it
            })
        }
    }

    /** Raises a take until its loudest moment sits just under full level. */
    fun boostTake(id: String) = viewModelScope.launch {
        val s = song.value ?: return@launch
        val c = s.clips.find { it.id == id } ?: return@launch
        val parts = if (c.group.isEmpty()) listOf(c) else s.passes(c.group)
        val peak = withContext(Dispatchers.Default) {
            val buf = audio.get(s.id, c.fileId) ?: return@withContext 0
            var m = 1
            for (p in parts) {
                val a = p.skip.toInt().coerceAtLeast(0)
                val b = minOf(buf.capacity().toLong(), p.skip + p.length).toInt()
                for (i in a until b) {
                    val v = kotlin.math.abs(buf.get(i).toInt())
                    if (v > m) m = v
                }
            }
            m
        }
        if (peak <= 1) return@launch
        val db = (20 * kotlin.math.log10(0.89 * 32767 / peak)).toFloat()
        setTakeGain(id, (db * 2).toInt() / 2f)
    }

    /** Hides or shows a take's video on playback (all passes of a loop recording together). */
    fun toggleVideoHidden(id: String) {
        val c = song.value?.clips?.find { it.id == id } ?: return
        val hide = !c.videoHidden
        updateSong { s ->
            s.copy(clips = s.clips.map {
                if (it.id == id || (c.group.isNotEmpty() && it.group == c.group)) it.copy(videoHidden = hide) else it
            })
        }
    }

    /** Shows one pass of a loop recording and hides the others. */
    fun selectPass(id: String) {
        val c = song.value?.clips?.find { it.id == id } ?: return
        if (c.group.isEmpty()) return
        updateSong { s ->
            s.copy(clips = s.clips.map { if (it.group == c.group) it.copy(hidden = it.id != id) else it })
        }
        if (lastTake.value != null) lastTake.value = id
    }

    fun dismissTake() {
        lastTake.value = null
    }

    // ------------------------------------------------------------ mixer

    fun setVolume(t: Int, v: Float) = updateSong { s ->
        s.copy(tracks = s.tracks.mapIndexed { i, ts -> if (i == t) ts.copy(volume = v) else ts })
    }

    fun setTrack(t: Int, f: (app.demotape.data.TrackState) -> app.demotape.data.TrackState) = updateSong { s ->
        s.copy(tracks = s.tracks.mapIndexed { i, ts -> if (i == t) f(ts) else ts })
    }

    fun toggleMute(t: Int) = updateSong { s ->
        s.copy(tracks = s.tracks.mapIndexed { i, ts -> if (i == t) ts.copy(muted = !ts.muted) else ts })
    }

    // ------------------------------------------------------------ tuner

    fun openTuner() = viewModelScope.launch {
        if (!micAllowed()) return@launch
        engine.openInput()
        tuner.start(viewModelScope)
        tunerOpen.value = true
    }

    fun closeTuner() {
        tuner.stop()
        tunerOpen.value = false
        if (armed.value < 0 && take == null && cal.value == null) engine.closeInput()
    }

    fun setA4(hz: Float) {
        val v = hz.coerceIn(415f, 466f)
        tuner.a4 = v
        tunerA4.value = v
        ctx.getSharedPreferences("tuner", 0).edit().putFloat("a4", v).apply()
    }

    // ------------------------------------------------------------ loop

    /**
     * Turns looping on or off. With no loop yet, loops what's on screen ([viewStart]..[viewEnd]),
     * snapped to bars when the metronome is on.
     */
    fun toggleLoop(viewStart: Long, viewEnd: Long) {
        val s = song.value ?: return
        if (s.loopOn) {
            updateSong { it.copy(loopOn = false) }
            return
        }
        if (s.hasLoop) {
            updateSong { it.copy(loopOn = true) }
            return
        }
        val span = (viewEnd - viewStart).coerceAtLeast(SR.toLong())
        var a = viewStart + span / 10
        var b = viewEnd - span / 10
        if (s.click) {
            val bar = Metronome.beatLength(s.bpm) * s.beatsPerBar
            a = Math.round(Math.floor(a / bar) * bar)
            b = Math.round(Math.ceil(b / bar) * bar).coerceAtLeast(a + Math.round(bar))
        }
        updateSong { it.copy(loopOn = true, loopStart = a.coerceAtLeast(0L), loopEnd = b) }
    }

    fun setLoopRange(start: Long, end: Long) {
        if (end - start < SR / 4) return
        updateSong(save = false) { it.copy(loopStart = start.coerceAtLeast(0L), loopEnd = end) }
    }

    fun saveSoon() {
        song.value?.let { scheduleSave(it) }
    }

    // ------------------------------------------------------------ drums

    fun setDrums(f: (Song) -> Song) = updateSong { f(it) }

    fun toggleDrums() = updateSong { s ->
        if (!s.drumsOn && !s.hasDrumPattern) applyPreset(s, Drums.presets[0].first).copy(drumsOn = true)
        else s.copy(drumsOn = !s.drumsOn)
    }

    fun applyPreset(s: Song, name: String): Song {
        val rows = Drums.presets.firstOrNull { it.first == name }?.second ?: return s
        return s.copy(drumRows = rows.map { Drums.tile(it, s) }, drumSwing = Drums.presetSwing(name))
    }

    // ------------------------------------------------------------ metronome

    fun setMetronome(f: (Song) -> Song) = updateSong { f(it) }

    // ------------------------------------------------------------ nudge

    fun openNudge(id: String) = launchT {
        if (take != null) return@launchT
        val s = song.value ?: return@launchT
        val c = s.clips.find { it.id == id } ?: return@launchT
        if (nudge.value != null) closeNudgeLocked(nudge.value?.remember == true)
        lastTake.value = null
        nudge.value = NudgeState(
            clipId = id,
            track = c.track,
            atOpen = c.nudge,
            remember = c.device.isNotEmpty() && c.device == outputs.current.value.key,
        )
    }

    fun nudgeBy(frames: Long) {
        val n = nudge.value ?: return
        updateSong(save = false) { s ->
            s.copy(clips = s.clips.map {
                if (it.id == n.clipId) it.copy(nudge = (it.nudge + frames).coerceIn(-SR * 2L, SR * 2L)) else it
            })
        }
    }

    fun resetNudge() {
        val n = nudge.value ?: return
        updateSong(save = false) { s ->
            s.copy(clips = s.clips.map { if (it.id == n.clipId) it.copy(nudge = 0L) else it })
        }
    }

    fun setRemember(b: Boolean) {
        nudge.value = nudge.value?.copy(remember = b)
    }

    fun closeNudge() = launchT { closeNudgeLocked(nudge.value?.remember == true) }

    private suspend fun closeNudgeLocked(remember: Boolean) {
        val n = nudge.value ?: return
        nudge.value = null
        val c = song.value?.clips?.find { it.id == n.clipId }
        if (remember && c != null && c.nudge != n.atOpen && c.device.isNotEmpty()) {
            val delta = c.nudge - n.atOpen
            val old = sync.get(c.device)
            sync.put(
                c.device,
                SyncInfo(
                    offset = (old?.offset ?: 0L) - delta,
                    delayMs = (old?.delayMs ?: 0) - (delta * 1000 / SR).toInt(),
                    synced = true,
                )
            )
        }
        song.value?.let { scheduleSave(it) }
    }

    // ------------------------------------------------------------ sync check

    fun openCalibration() {
        if (take != null) return
        cal.value = CalState.Intro(null)
    }

    fun runCalibration() = launchT {
        val then = cal.value?.thenRecord
        if (nudge.value != null) closeNudgeLocked(false)
        engine.stopPlayback()
        val wasOpen = engine.inputOpen
        engine.openInput()
        engine.awaitInput()?.let {
            cal.value = CalState.Failed("Couldn't start the microphone: $it", then)
            return@launchT
        }
        val clicks = Calibration.clicks()
        cal.value = CalState.Running(clicks, then)
        val r = engine.calibrate(clicks)
        if (!wasOpen && armed.value < 0) engine.closeInput()
        val dev = outputs.current.value
        cal.value = when (r) {
            is Calibration.Result.Ok -> {
                sync.put(dev.key, SyncInfo(r.offset, r.delayMs, synced = true))
                CalState.Done(r.delayMs, then)
            }

            is Calibration.Result.Fail -> CalState.Failed(r.reason, then)
        }
    }

    fun closeCalibration(thenRecord: Boolean) {
        val st = cal.value ?: return
        if (st is CalState.Running) return
        cal.value = null
        val t = st.thenRecord
        if (thenRecord && t != null) launchT { startTakeLocked(t) }
    }

    // ------------------------------------------------------------ export

    fun export(format: Exporter.Format, save: Boolean) = viewModelScope.launch {
        val s = song.value ?: return@launch
        if (s.length <= 0) {
            msg("Nothing to export yet")
            return@launch
        }
        busy.value = "Mixing down…"
        try {
            val f = withContext(Dispatchers.Default) { Exporter.render(ctx, s, audio, format) }
            if (save) {
                withContext(Dispatchers.IO) { Exporter.saveToDownloads(ctx, f, format) }
                msg("Saved to Downloads/Demo-tape")
            } else {
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = format.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, f.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(
                    Intent.createChooser(send, s.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: Exception) {
            msg("Export failed: ${e.message}")
        } finally {
            busy.value = null
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun onBackground() = launchT {
        if (take != null) stopTakeLocked()
        if (engine.playing.value) {
            engine.stopPlayback()
            playhead.value = if (nudge.value != null) lastPlayFrom else engine.position.value
        }
        engine.closeInput()
        song.value?.let { s ->
            saveJob?.cancel()
            withContext(Dispatchers.IO) { repo.save(s) }
        }
    }

    fun onForegroundTuner() {
        if (tunerOpen.value && micAllowed()) engine.openInput()
    }

    fun onForeground() {
        onForegroundTuner()
        if (armed.value >= 0 && song.value != null) engine.openInput()
    }

    override fun onCleared() {
        engine.closeInput()
    }
}
