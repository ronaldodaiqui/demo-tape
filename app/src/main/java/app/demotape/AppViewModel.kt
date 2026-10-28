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
import app.demotape.data.PatternLibrary
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
data class NudgeState(
    val clipId: String,
    val track: Int,
    val atOpen: Long,
    val remember: Boolean,
    /** The take's trim when line-up opened, so trimming can be undone. */
    val openStart: Long = 0L,
    val openSkip: Long = 0L,
    val openLength: Long = 0L,
    val openVideo: Long = 0L,
)

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

    // ------------------------------------------------------------ interruptions

    private val noisy = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            interrupt(null)
        }
    }

    private val audioManager = app.getSystemService(android.media.AudioManager::class.java)
    private val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            // A call, an alarm, or another app playing: stop and keep what was recorded.
            // Brief "duck" requests (notification sounds) are ignored.
            if (change == android.media.AudioManager.AUDIOFOCUS_LOSS ||
                change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) interrupt(null)
        }
        .build()

    init {
        // Headphones unplugged or Bluetooth dropped: stop instead of blasting the phone speaker.
        androidx.core.content.ContextCompat.registerReceiver(
            app,
            noisy,
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun takeFocus() {
        audioManager.requestAudioFocus(focusRequest)
    }

    /** Stops playback (and recording, keeping the take). */
    private fun interrupt(reason: String?) = launchT {
        if (take != null) {
            stopTakeLocked()
            if (reason != null) msg(reason)
        } else if (engine.playing.value) {
            engine.stopPlayback()
            playhead.value = engine.position.value
        }
    }

    private fun spaceLeft(): Long = ctx.filesDir.usableSpace

    private fun msg(s: String) {
        messages.tryEmit(s)
    }

    private fun launchT(block: suspend () -> Unit) {
        viewModelScope.launch { transport.withLock { block() } }
    }

    private fun updateSong(save: Boolean = true, f: (Song) -> Song) {
        val s = song.value ?: return
        val n = f(s).copy(modified = System.currentTimeMillis())
        remember(s, n)
        song.value = n
        source?.song = n
        syncLoop(n)
        if (save) scheduleSave(n)
    }

    // ------------------------------------------------------------ undo / redo

    private val undoStack = ArrayDeque<Pair<Song, String>>()
    private val redoStack = ArrayDeque<Pair<Song, String>>()
    private var lastChange = 0L
    private var lastLabel = ""
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)

    /**
     * Keeps the state before each change. A run of quick changes of the same kind (dragging a
     * fader, typing notes, sliding the tempo) counts as one step.
     */
    private fun remember(old: Song, new: Song) {
        val label = describe(old, new) ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastChange > 700 || label != lastLabel || undoStack.isEmpty()) {
            undoStack.addLast(old to label)
            while (undoStack.size > 100) undoStack.removeFirst()
        }
        lastChange = now
        lastLabel = label
        redoStack.clear()
        canUndo.value = true
        canRedo.value = false
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        canUndo.value = false
        canRedo.value = false
        lastLabel = ""
    }

    fun undo() = launchT {
        if (take != null) return@launchT
        val cur = song.value ?: return@launchT
        val (prev, label) = undoStack.removeLastOrNull() ?: return@launchT
        redoStack.addLast(cur to label)
        restore(prev)
        msg("Undid $label · hold ↶ to redo")
    }

    fun redo() = launchT {
        if (take != null) return@launchT
        val cur = song.value ?: return@launchT
        val (next, label) = redoStack.removeLastOrNull() ?: return@launchT
        undoStack.addLast(cur to label)
        restore(next)
        msg("Redid $label")
    }

    private suspend fun restore(s: Song) {
        val n = s.copy(modified = System.currentTimeMillis())
        song.value = n
        source?.song = n
        syncLoop(n)
        scheduleSave(n)
        lastChange = 0L
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = redoStack.isNotEmpty()
        if (nudge.value?.clipId?.let { id -> n.clips.none { it.id == id } } == true) nudge.value = null
        if (lastTake.value?.let { id -> n.clips.none { it.id == id } } == true) lastTake.value = null
        // Bring back waveforms for takes that return.
        val missing = n.clips.map { it.fileId }.toSet().filter { it !in peaks }
        if (missing.isNotEmpty()) {
            val loaded = withContext(Dispatchers.IO) { missing.mapNotNull { f -> repo.loadPeaks(n.id, f)?.let { f to it } } }
            loaded.forEach { (f, p) -> peaks[f] = p }
        }
    }

    /** A short name for what changed, or null if nothing the user would undo did. */
    private fun describe(a: Song, b: Song): String? {
        if (a.clips != b.clips) {
            val ai = a.clips.associateBy { it.id }
            val bi = b.clips.associateBy { it.id }
            val added = b.clips.filter { it.id !in ai }
            val removed = a.clips.filter { it.id !in bi }
            return when {
                added.isNotEmpty() -> "take on track ${added[0].track + 1}"
                removed.isNotEmpty() -> "deleted take on track ${removed[0].track + 1}"
                else -> {
                    val ch = b.clips.first { ai[it.id] != it }
                    val o = ai.getValue(ch.id)
                    when {
                        o.hidden != ch.hidden -> "pass change"
                        o.gainDb != ch.gainDb -> "take volume"
                        o.videoHidden != ch.videoHidden -> "video change"
                        o.length == ch.length && o.skip == ch.skip -> "line-up"
                        else -> "trim"
                    }
                }
            }
        }
        if (a.bpm != b.bpm) return "tempo change"
        if (a.beatsPerBar != b.beatsPerBar) return "beats per bar"
        if (a.click != b.click || a.countIn != b.countIn || a.clickVolume != b.clickVolume) return "metronome change"
        if (a.drumsOn != b.drumsOn || a.drumRows != b.drumRows || a.drumBars != b.drumBars ||
            a.drumVolume != b.drumVolume || a.drumSwing != b.drumSwing
        ) return "drums change"
        if (a.loopOn != b.loopOn || a.loopStart != b.loopStart || a.loopEnd != b.loopEnd) return "loop change"
        if (a.tracks != b.tracks) {
            val t = (0 until TRACKS).first { a.tracks[it] != b.tracks[it] }
            val x = a.tracks[t]
            val y = b.tracks[t]
            val what = when {
                x.volume != y.volume -> "fader"
                x.muted != y.muted -> "mute"
                x.solo != y.solo -> "solo"
                x.name != y.name -> "name"
                x.pan != y.pan -> "pan"
                x.trim != y.trim -> "trim level"
                else -> "tone"
            }
            return "track ${t + 1} $what"
        }
        if (a.notes != b.notes) return "notes edit"
        if (a.name != b.name) return "rename"
        return null
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

    fun newSong() = viewModelScope.launch { createAndOpen() }

    private suspend fun createAndOpen() {
        val base = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        val names = withContext(Dispatchers.IO) { repo.list() }.map { it.name }.toSet()
        var name = base
        var i = 2
        while (name in names) name = "$base (${i++})"
        val s = withContext(Dispatchers.IO) { repo.create(name) }
        openSongNow(s.id)
    }

    /**
     * Instant capture: a fresh song, already recording on track 1. Used by the home-screen
     * widget, the app-icon shortcut and the record button on the song list. Mic permission
     * must already be granted.
     */
    fun quickCapture() = launchT {
        if (take != null || cal.value != null) return@launchT
        if (!micAllowed()) return@launchT
        // Put away whatever song is open.
        nudge.value = null
        engine.stopPlayback()
        videoMode.value = false
        video.unbind()
        song.value?.let { s ->
            saveJob?.cancel()
            withContext(Dispatchers.IO) { repo.save(s) }
        }
        createAndOpen()
        armed.value = 0
        startTakeLocked(0)
        songs.value = withContext(Dispatchers.IO) { repo.list() }
    }

    fun openSong(id: String) = viewModelScope.launch { openSongNow(id) }

    /**
     * If the app died while recording, the take's audio is still on disk but not in the song.
     * Adds it back (placed where recording started; Line up can fine-tune it). Returns the song
     * with the take and its track, or null if there was nothing to recover.
     */
    private fun recoverTake(s: Song): Pair<Song, Int>? {
        val m = repo.readRecording(s.id) ?: return null
        repo.clearRecording(s.id)
        return try {
            val id = m.getString("id")
            if (s.clips.any { it.fileId == id }) return null
            val buf = audio.get(s.id, id) ?: return null
            val frames = buf.capacity().toLong()
            val skip = m.optLong("skip", 0L).coerceIn(0L, frames)
            if (frames - skip < SR / 2) return null
            val track = m.getInt("track").coerceIn(0, TRACKS - 1)
            repo.savePeaks(s.id, id, app.demotape.audio.PeakBuilder.fromSamples(buf))
            val clip = Clip(id, track, m.getLong("from"), 0L, frames - skip, System.currentTimeMillis(), m.optString("device", ""), skip)
            val n = s.copy(clips = s.clips + clip)
            repo.save(n)
            n to track
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun openSongNow(id: String) {
        var recovered: Int? = null
        val loaded = withContext(Dispatchers.IO) {
            val s0 = repo.load(id) ?: return@withContext null
            val r = recoverTake(s0)
            val s = r?.first ?: s0
            recovered = r?.second
            repo.cleanup(s)
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
        clearHistory()
        song.value = loaded.first
        recovered?.let { msg("Recovered an unfinished take on track ${it + 1}") }
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
            withContext(Dispatchers.IO) {
                repo.save(s)
                repo.cleanup(s)
            }
        }
        clearHistory()
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
        val looping = s.loopOn && s.hasLoop
        val from = when {
            looping && fromIn >= s.loopEnd -> s.loopStart
            !looping && len > 0 && fromIn >= len - SR / 10 -> 0L
            else -> fromIn.coerceAtLeast(0L)
        }
        src.silentTrack = -1
        playhead.value = from
        lastPlayFrom = from
        takeFocus()
        val p = engine.play(src, from, loops = true, stopAt = playUntil(s))
        endWatch?.cancel()
        endWatch = viewModelScope.launch {
            p.done.await()
            if (p.endedNaturally) playhead.value = from
        }
    }

    /**
     * Where playback stops by itself: the end of the recording, unless the click or drums are on
     * (or nothing's recorded yet), in which case it keeps going so you can practice or write on.
     */
    private fun playUntil(s: Song): Long =
        if (s.length > 0 && !s.click && !s.drumsOn) s.length else -1L

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
        // Sync only matters when there's something to line up with.
        val needsSync = s.clips.isNotEmpty() || s.click || s.drumsOn
        if (needsSync && dev.kind == OutputKind.BLUETOOTH && sync.get(dev.key) == null) {
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
        if (spaceLeft() < 60L * 1024 * 1024) {
            msg("Your phone is almost out of space. Free some up to record.")
            return
        }
        takeFocus()
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

    /** Remembers the take in progress, so it can be recovered if the app dies mid-take. */
    private fun markTake(tk: TakeCtx) {
        val s = song.value ?: return
        try {
            repo.markRecording(
                s.id,
                org.json.JSONObject()
                    .put("id", tk.id).put("track", tk.track).put("from", tk.from)
                    .put("skip", (tk.kRec - tk.kCap).coerceAtLeast(0L)).put("device", tk.device),
            )
        } catch (_: Exception) {
        }
    }

    private fun startFilming(tk: TakeCtx) {
        markTake(tk)
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
                // Safety: stop cleanly (keeping the take) if the phone fills up or the mic dies.
                val problem = when {
                    tk.sink.error || spaceLeft() < 25L * 1024 * 1024 -> "Phone storage is full. Recording stopped, take kept."
                    engine.inputFailure != null -> "The microphone stopped. Take kept."
                    else -> null
                }
                if (problem != null) {
                    interrupt(problem)
                    break
                }
                delay(100)
            }
        }
    }

    /**
     * The record button while recording: punch out. Playback keeps going when another track has
     * audio under the playhead, so you can punch in again. The play/stop button stops everything.
     */
    fun stopRecording() = launchT {
        val s = song.value ?: return@launchT
        val tk = take ?: return@launchT
        // Keep rolling only if another track has audio right under the playhead, so you can punch
        // back in. Otherwise (a first take, even with the click or drums on) stop.
        val pos = engine.position.value
        val keep = s.visibleClips.any { it.track != tk.track && s.audible(it.track) && pos >= it.pos && pos < it.end }
        stopTakeLocked(keepPlaying = keep)
    }

    private suspend fun stopTakeLocked(keepPlaying: Boolean = false) {
        val tk = take ?: return
        take = null
        liveJob?.cancel()
        song.value?.let { s -> withContext(Dispatchers.IO) { repo.clearRecording(s.id) } }
        val recPairs = engine.detachSink()
        if (keepPlaying && engine.playing.value) {
            tk.player.stopCollecting()
        } else {
            engine.stopPlayback()
        }
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
        if (keepPlaying && engine.playing.value) {
            // Still rolling: stop at the end of the song like normal playback, then return
            // to where the take started.
            lastPlayFrom = tk.from
            val p = tk.player
            p.stopAt = playUntil(song.value ?: s)
            endWatch?.cancel()
            endWatch = viewModelScope.launch {
                p.done.await()
                if (p.endedNaturally) playhead.value = tk.from
            }
        }
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
        // The audio stays on disk until the song is closed, so undo can bring the take back.
        if (lastTake.value in ids) lastTake.value = null
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

    fun toggleSolo(t: Int) = setTrack(t) { it.copy(solo = !it.solo) }

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
        if (s.gridOn) {
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

    // ------------------------------------------------------------ notes

    fun setNotes(text: String) = updateSong { it.copy(notes = text) }

    // ------------------------------------------------------------ bounce

    /**
     * Mixes everything you hear into one new take on [dest]. The source tracks are muted, not
     * deleted, so it sounds the same and they can be cleared when you need the room. One undo
     * puts it all back.
     */
    fun bounce(dest: Int, withDrums: Boolean) = launchT {
        if (take != null) return@launchT
        val s = song.value ?: return@launchT
        val sources = (0 until TRACKS).filter { t -> s.audible(t) && s.visibleClips.any { it.track == t } }
        val drums = withDrums && s.drumsOn && s.hasDrumPattern
        if (sources.isEmpty() && !drums) {
            msg("Nothing to bounce")
            return@launchT
        }
        if (nudge.value != null) closeNudgeLocked(false)
        engine.stopPlayback()
        busy.value = "Bouncing…"
        try {
            val id = newId()
            val file = repo.clipFile(s.id, id).also { it.parentFile?.mkdirs() }
            val (start, len, pk) = withContext(Dispatchers.Default) { Exporter.bounce(s, audio, drums, file) }
            if (len < SR / 4) {
                file.delete()
                msg("Nothing to bounce")
                return@launchT
            }
            withContext(Dispatchers.IO) {
                repo.savePeaks(s.id, id, pk)
                audio.get(s.id, id)
            }
            peaks[id] = pk
            val clip = Clip(id, dest, start, 0L, len, System.currentTimeMillis(), "", 0L)
            updateSong { so ->
                so.copy(
                    // The bounce replaces whatever was on the destination track.
                    clips = so.clips.filter { it.track != dest } + clip,
                    tracks = so.tracks.mapIndexed { i, ts ->
                        when {
                            i == dest -> app.demotape.data.TrackState(name = ts.name.ifBlank { "Bounce" })
                            i in sources -> ts.copy(muted = true, solo = false)
                            else -> ts.copy(solo = false)
                        }
                    },
                    drumsOn = if (drums) false else so.drumsOn,
                )
            }
            val names = sources.filter { it != dest }.map { it + 1 }
            msg(
                "Bounced to track ${dest + 1}" +
                    (if (names.isNotEmpty()) ". Muted ${names.joinToString(", ")}" else "") +
                    (if (drums) ", drums off" else "")
            )
        } catch (e: Exception) {
            msg("Bounce failed: ${e.message}")
        } finally {
            busy.value = null
        }
    }

    fun setTrackName(t: Int, name: String) = setTrack(t) { it.copy(name = name.take(16)) }

    // ------------------------------------------------------------ drums

    fun setDrums(f: (Song) -> Song) = updateSong { f(it) }

    val patternLibrary = PatternLibrary(app)

    /** Saves the song's drum pattern to the library, for any song. */
    fun savePattern(name: String) {
        val s = song.value ?: return
        val n = name.trim().ifEmpty { return }
        if (!s.hasDrumPattern) return
        val spb = Drums.stepsPerBar(s)
        patternLibrary.save(
            app.demotape.data.SavedPattern(
                name = n,
                rows = List(Drums.VOICES) { v -> (s.drumRows.getOrNull(v) ?: "").padEnd(spb * s.drumBars, '.').take(spb * s.drumBars) },
                bars = s.drumBars,
                stepsPerBar = spb,
                swing = s.drumSwing,
            )
        )
        msg("Saved “$n”")
    }

    fun deletePattern(name: String) = patternLibrary.delete(name)

    /** Uses a saved pattern in this song, fitting each bar to the song's beats per bar. */
    fun useSavedPattern(p: app.demotape.data.SavedPattern) = updateSong { s ->
        val spb = Drums.stepsPerBar(s)
        val rows = List(Drums.VOICES) { v ->
            val src = p.rows.getOrNull(v) ?: ""
            val sb = StringBuilder()
            for (b in 0 until p.bars) {
                val bar = src.drop(b * p.stepsPerBar).take(p.stepsPerBar)
                sb.append(bar.padEnd(spb, '.').take(spb))
            }
            sb.toString().padEnd(Drums.MAX_STEPS, '.').take(Drums.MAX_STEPS)
        }
        s.copy(drumRows = rows, drumBars = p.bars, drumSwing = p.swing, drumsOn = true)
    }

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
            openStart = c.start, openSkip = c.skip, openLength = c.length, openVideo = c.videoOffset,
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

    /**
     * Trims the take being lined up: [edge] 1 moves its start, 2 its end, to song frame [frame].
     * Nothing is deleted; the whole recording stays in the file, so an edge can always come back.
     */
    fun trimTo(edge: Int, frame: Long) {
        val n = nudge.value ?: return
        val s = song.value ?: return
        val c = s.clips.find { it.id == n.clipId } ?: return
        val cap = audio.get(s.id, c.fileId)?.capacity()?.toLong() ?: return
        val minLen = SR / 10L
        val nc = if (edge == 1) {
            val d = (frame - c.pos).coerceIn(-c.skip, c.length - minLen)
            c.copy(start = c.start + d, skip = c.skip + d, length = c.length - d, videoOffset = c.videoOffset + d)
        } else {
            val len = (frame - c.pos).coerceIn(minLen, cap - c.skip)
            c.copy(length = len)
        }
        if (nc != c) updateSong(save = false) { so -> so.copy(clips = so.clips.map { if (it.id == c.id) nc else it }) }
    }

    /** Puts the trim back to how it was when line-up opened. */
    fun undoTrim() {
        val n = nudge.value ?: return
        updateSong { s ->
            s.copy(clips = s.clips.map {
                if (it.id == n.clipId) it.copy(start = n.openStart, skip = n.openSkip, length = n.openLength, videoOffset = n.openVideo)
                else it
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

    enum class ExportKind(val mime: String, val busy: String) {
        M4A("audio/mp4", "Mixing down…"),
        WAV("audio/wav", "Mixing down…"),
        STEMS("application/zip", "Rendering stems…"),
        BACKUP("application/octet-stream", "Packing up the song…"),
    }

    fun export(kind: ExportKind, save: Boolean) = viewModelScope.launch {
        val s = song.value ?: return@launch
        if (s.length <= 0 && kind != ExportKind.BACKUP) return@launch
        busy.value = kind.busy
        try {
            val f = withContext(Dispatchers.Default) {
                when (kind) {
                    ExportKind.M4A -> Exporter.render(ctx, s, audio, Exporter.Format.M4A)
                    ExportKind.WAV -> Exporter.render(ctx, s, audio, Exporter.Format.WAV)
                    ExportKind.STEMS -> Exporter.stems(ctx, s, audio)
                    ExportKind.BACKUP -> {
                        saveJob?.cancel()
                        repo.save(s)
                        Exporter.backupFile(ctx, s).also { out -> out.outputStream().use { repo.exportBackup(s.id, it) } }
                    }
                }
            }
            if (save) {
                withContext(Dispatchers.IO) { Exporter.saveToDownloads(ctx, f, kind.mime) }
                msg("Saved to Downloads/Demo-tape")
            } else {
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = kind.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, f.name)
                    if (kind == ExportKind.M4A || kind == ExportKind.WAV) {
                        putExtra(Intent.EXTRA_SUBJECT, s.name)
                    }
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

    /** Imports a .demotape backup and opens it. */
    fun importSong(uri: android.net.Uri) = viewModelScope.launch {
        busy.value = "Importing…"
        val s = try {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { repo.importBackup(it) }
            }
        } catch (e: Exception) {
            null
        } finally {
            busy.value = null
        }
        if (s == null) {
            msg("That file isn't a Demo-tape song backup")
            return@launch
        }
        songs.value = withContext(Dispatchers.IO) { repo.list() }
        openSongNow(s.id)
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
        try {
            ctx.unregisterReceiver(noisy)
        } catch (_: Exception) {
        }
        audioManager.abandonAudioFocusRequest(focusRequest)
        engine.closeInput()
    }
}
