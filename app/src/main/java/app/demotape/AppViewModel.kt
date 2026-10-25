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
import app.demotape.audio.FileSink
import app.demotape.audio.Metronome
import app.demotape.audio.SR
import app.demotape.audio.SongSource
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
    val sink: FileSink,
    val player: AudioEngine.Player,
    val device: String,
)

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
        if (save) scheduleSave(n)
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
        val base = "Demo " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
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
                audio.get(s.id, c.id)
                repo.loadPeaks(s.id, c.id)?.let { pk[c.id] = it }
            }
            s to pk
        } ?: return
        peaks.clear()
        peaks.putAll(loaded.second)
        playhead.value = 0L
        armed.value = -1
        lastTake.value = null
        nudge.value = null
        source = SongSource(loaded.first, audio).also { it.metronome = true }
        song.value = loaded.first
    }

    fun closeSong() = launchT {
        if (take != null) stopTakeLocked()
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
        if (len <= 0) {
            msg("Nothing recorded yet")
            return
        }
        val from = if (fromIn >= len - SR / 10) 0L else fromIn.coerceAtLeast(0L)
        src.silentTrack = -1
        playhead.value = from
        lastPlayFrom = from
        val p = engine.play(src, from, stopAt = len)
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
        engine.stopPlayback()
        engine.openInput()
        engine.awaitInput()?.let {
            msg("Couldn't start the microphone: $it")
            return
        }
        val id = newId()
        val file = repo.clipFile(s.id, id).also { it.parentFile?.mkdirs() }
        val sink = FileSink(file)
        src.silentTrack = t
        val from = playhead.value.coerceAtLeast(0L)
        // With the metronome on, playback starts a bar early for a count-in.
        val bar = if (s.click && s.countIn)
            Math.round(Metronome.beatLength(s.bpm) * s.beatsPerBar) else 0L
        val playFrom = from - bar
        src.countInUntil = if (bar > 0) from else Long.MIN_VALUE
        val dev = outputs.current.value
        val p = engine.startTake(src, playFrom, sink) ?: run {
            sink.finish()
            file.delete()
            src.silentTrack = -1
            src.countInUntil = Long.MIN_VALUE
            msg("Couldn't start recording")
            return
        }
        take = TakeCtx(id, t, from, playFrom, sink, p, dev.key)
        countInTo.value = if (bar > 0) from else null
        armed.value = t
        recordingTrack.value = t
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            while (isActive) {
                liveTake.value = LiveTake(t, playFrom, sink.peaks.snapshot())
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
        val d = AudioEngine.alignment(playPairs, recPairs, tk.player.startNanos)
            ?: (tk.playFrom - tk.sink.startFrame)
        val extra = sync.get(tk.device)?.offset ?: 0L
        val rawStart = tk.sink.startFrame + d - extra
        // The take begins exactly where record was pressed; count-in and pre-roll audio is skipped,
        // so punching in never eats into what came before.
        val skip = (tk.from - rawStart).coerceAtLeast(0L)
        val frames = tk.sink.frames - skip
        if (frames < SR / 4) {
            discard()
            return
        }
        val start = rawStart + skip
        val pk = tk.sink.peaks.snapshot()
        withContext(Dispatchers.IO) {
            repo.savePeaks(s.id, tk.id, pk)
            audio.get(s.id, tk.id)
        }
        peaks[tk.id] = pk
        val clip = Clip(tk.id, tk.track, start, 0L, frames, System.currentTimeMillis(), tk.device, skip)
        updateSong { it.copy(clips = it.clips + clip) }
        lastTake.value = tk.id
        playhead.value = tk.from
    }

    fun undoLastTake() = launchT {
        if (take != null) return@launchT
        val s = song.value ?: return@launchT
        val c = s.clips.maxByOrNull { it.created } ?: return@launchT
        removeClipsLocked(listOf(c))
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
        updateSong { it.copy(clips = it.clips.filter { c -> c.id !in ids }) }
        val sid = song.value?.id ?: return
        for (c in cs) {
            peaks.remove(c.id)
            audio.evict(c.id)
        }
        if (lastTake.value in ids) lastTake.value = null
        withContext(Dispatchers.IO) { cs.forEach { repo.deleteClip(sid, it.id) } }
    }

    fun dismissTake() {
        lastTake.value = null
    }

    // ------------------------------------------------------------ mixer

    fun setVolume(t: Int, v: Float) = updateSong { s ->
        s.copy(tracks = s.tracks.mapIndexed { i, ts -> if (i == t) ts.copy(volume = v) else ts })
    }

    fun toggleMute(t: Int) = updateSong { s ->
        s.copy(tracks = s.tracks.mapIndexed { i, ts -> if (i == t) ts.copy(muted = !ts.muted) else ts })
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

    fun onForeground() {
        if (armed.value >= 0 && song.value != null) engine.openInput()
    }

    override fun onCleared() {
        engine.closeInput()
    }
}
