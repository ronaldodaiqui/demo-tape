@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.demotape.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.demotape.AppViewModel
import app.demotape.NudgeState
import app.demotape.audio.Metronome
import app.demotape.audio.SR
import app.demotape.data.Clip
import app.demotape.data.OutputDevice
import app.demotape.data.OutputKind
import app.demotape.data.Song
import app.demotape.data.SyncInfo
import app.demotape.data.TRACKS
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongScreen(vm: AppViewModel, song: Song) {
    val ctx = LocalContext.current
    val enginePos = vm.engine.position.collectAsState()
    val playheadS = vm.playhead.collectAsState()
    val playingS = vm.engine.playing.collectAsState()
    val levelS = vm.engine.inputLevel.collectAsState()
    val playing = playingS.value
    val recordingTrack by vm.recordingTrack.collectAsState()
    val armed by vm.armed.collectAsState()
    val output by vm.output.collectAsState()
    val syncInfo by vm.currentSync.collectAsState()
    val nudge by vm.nudge.collectAsState()
    val lastTake by vm.lastTake.collectAsState()
    val live by vm.liveTake.collectAsState()
    val prompt by vm.syncPrompt.collectAsState()
    val cal by vm.cal.collectAsState()
    val recording = recordingTrack >= 0
    val position: () -> Long = { if (playingS.value) enginePos.value else playheadS.value }

    // Short notices show as a small pill at the top of the timeline and fade on their own.
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeId by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        vm.messages.collect {
            notice = it
            noticeId++
        }
    }
    LaunchedEffect(noticeId) {
        if (notice != null) {
            kotlinx.coroutines.delay(1600)
            notice = null
        }
    }

    // Keep the screen on while audio is running.
    val view = LocalView.current
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    var pendingMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingMic?.invoke()
        pendingMic = null
    }
    val withMic: (() -> Unit) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingMic = action
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Track 1 (or the first empty track) starts armed: ask for the mic right away.
    LaunchedEffect(song.id) {
        if (vm.armed.value >= 0) withMic { vm.openArmedInput() }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var bouncing by remember { mutableStateOf(false) }
    var syncSheet by remember { mutableStateOf(false) }
    var clipMenu by remember { mutableStateOf<Clip?>(null) }
    var markerMenu by remember { mutableStateOf<String?>(null) }
    var markerNaming by remember { mutableStateOf<String?>(null) }
    var trackMenu by remember { mutableStateOf(-1) }
    var metronomeOpen by remember { mutableStateOf(false) }
    var toneFor by remember { mutableStateOf(-1) }
    var drumsOpen by remember { mutableStateOf(false) }
    val savedPatterns by vm.patternLibrary.patterns.collectAsState()
    var notesOpen by remember { mutableStateOf(false) }
    val unsaved by vm.unsaved.collectAsState()
    val closePrompt by vm.closePrompt.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val videoMode by vm.videoMode.collectAsState()
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.setVideoMode(true)
    }
    val toggleVideo = {
        if (videoMode) vm.setVideoMode(false)
        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) vm.setVideoMode(true)
        else camLauncher.launch(Manifest.permission.CAMERA)
    }
    val tunerOpen by vm.tunerOpen.collectAsState()
    val tunerReading by vm.tuner.reading.collectAsState()
    val a4 by vm.tunerA4.collectAsState()
    var viewport by remember { mutableStateOf(0L to (SR * 6L)) }
    val countInTo by vm.countInTo.collectAsState()

    if (notesOpen) {
        BackHandler { notesOpen = false }
        NotesScreen(
            song = song,
            position = position,
            playing = playing,
            recording = recording,
            armed = armed,
            onNotes = { vm.setNotes(it) },
            onTrackName = { t, n -> vm.setTrackName(t, n) },
            onPlay = { vm.togglePlay() },
            onRecord = { if (recording) vm.stopRecording() else withMic { vm.recordPressed() } },
            onClose = { notesOpen = false },
        )
        return
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Cream),
                navigationIcon = {
                    IconButton(onClick = { vm.requestClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Songs", tint = Cream)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            song.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).clickable(enabled = !recording) { renaming = true },
                        )
                        // Unsaved changes (only for songs opened from the list).
                        if (unsaved) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(7.dp).clip(CircleShape).background(MuteAmber))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { withMic { vm.openTuner() } }) {
                        Icon(TuningFork, contentDescription = "Tuner", tint = Cream)
                    }
                    // Tap to undo the last change, hold to redo.
                    val undoOn = (canUndo || canRedo) && !recording
                    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                enabled = undoOn,
                                onClick = { vm.undo() },
                                onLongClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    vm.redo()
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo (hold to redo)",
                            tint = if (canUndo && !recording) Cream else Faint,
                        )
                    }
                    IconButton(onClick = { notesOpen = true }) {
                        BadgedNotesIcon(hasNotes = song.notes.isNotBlank())
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Cream)
                        }
                        AppMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                            val hasTakes = song.clips.isNotEmpty()
                            AppMenuItem("Bounce…", Icons.Filled.MergeType, enabled = hasTakes) {
                                menuOpen = false; if (hasTakes && !recording) bouncing = true
                            }
                            AppMenuItem("Export…", Icons.Filled.IosShare, enabled = hasTakes) {
                                menuOpen = false; if (hasTakes && !recording) exporting = true
                            }
                            if (unsaved) {
                                AppMenuItem("Save", Icons.Filled.Save) { menuOpen = false; vm.save() }
                            }
                            AppMenuItem("Save as new song", Icons.Filled.FileCopy, enabled = !recording) {
                                menuOpen = false; if (!recording) vm.saveAsNew()
                            }
                            AppMenuItem("Rename", Icons.Filled.Edit) { menuOpen = false; renaming = true }
                            AppMenuItem("Headphones & sync", Icons.Filled.Headphones) { menuOpen = false; syncSheet = true }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Timeline keeps its height in every mode; the take bar floats over its bottom edge.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Timeline(
                    song = song,
                    peaks = vm.peaks,
                    samples = { vm.samples(it) },
                    position = position,
                    anchor = { if (playingS.value) vm.engine.anchor else null },
                    follow = playing,
                    live = live,
                    nudgeClip = nudge?.clipId,
                    onSeek = { vm.seek(it) },
                    onLongPressClip = { if (!recording) clipMenu = it },
                    onNudgeDrag = { vm.nudgeBy(it) },
                    onTrim = { edge, f -> vm.trimTo(edge, f) },
                    onTrimDone = { vm.saveSoon() },
                    loopOn = song.loopOn,
                    loopStart = song.loopStart,
                    loopEnd = song.loopEnd,
                    snapFrames = if (song.gridOn) Metronome.beatLength(song.bpm) else null,
                    onLoopChange = { a, b -> vm.setLoopRange(a, b) },
                    onLoopDone = { vm.saveSoon() },
                    onViewport = { a, b -> viewport = a to b },
                    onAddMarker = { f -> vm.addMarker(f)?.let { markerMenu = it } },
                    onMarkerMenu = { markerMenu = it.id },
                    onMarkerMove = { id, f -> vm.moveMarker(id, f) },
                    onMarkerMoveDone = { vm.saveSoon() },
                    modifier = Modifier.fillMaxSize(),
                )
                // Camera preview in video mode; otherwise video of the takes under the playhead.
                Box(Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 8.dp)) {
                    if (videoMode) {
                        CameraTile(vm.video, recording)
                    } else if (song.visibleClips.any { it.showsVideo }) {
                        VideoTiles(song, videoFile = { vm.videoFile(it) }, position = position, playing = playing)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = notice != null,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
                ) {
                    var shown by remember { mutableStateOf("") }
                    notice?.let { shown = it }
                    Text(
                        shown,
                        color = Cream, fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Surface2.copy(alpha = 0.95f))
                            .clickable { notice = null }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                val lt = lastTake
                if (nudge == null && lt != null && !recording) {
                    val c = song.clips.find { it.id == lt }
                    if (c != null) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                            TakeBar(
                                c, song.passes(c.group),
                                onLineUp = { vm.openNudge(lt) },
                                onPass = { vm.selectPass(it) },
                                onDismiss = { vm.dismissTake() },
                            )
                        }
                    }
                }
            }

            // Mixer, or the line-up controls in the same space.
            val n = nudge
            Box(Modifier.fillMaxWidth().height(230.dp)) {
                if (n != null) {
                    NudgePanel(
                        n, song, output.name,
                        onNudge = { vm.nudgeBy(it) }, onReset = { vm.resetNudge() }, onUndoTrim = { vm.undoTrim() },
                        onRemember = { vm.setRemember(it) }, onDone = { vm.closeNudge() },
                    )
                } else {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        for (t in 0 until TRACKS) {
                            ChannelStrip(
                                track = t,
                                state = song.tracks[t],
                                armed = armed == t,
                                recording = recordingTrack == t,
                                level = { levelS.value },
                                onArm = { withMic { vm.toggleArm(t) } },
                                onArmLong = { if (!recording && song.hasAudio(t)) trackMenu = t },
                                onMute = { vm.toggleMute(t) },
                                onSolo = { vm.toggleSolo(t) },
                                onTone = { toneFor = t },
                                onVolume = { vm.setVolume(t, it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Transport(
                position = position,
                playing = playing,
                recording = recording,
                countInTo = countInTo,
                beatLength = Metronome.beatLength(song.bpm),
                chips = {
                    MetronomeChip(
                        song,
                        onClick = { if (!recording) metronomeOpen = true },
                        onLongClick = { vm.setMetronome { it.copy(click = !it.click) } },
                    )
                    Spacer(Modifier.width(6.dp))
                    DrumsChip(song, onClick = { drumsOpen = true }, onLongClick = { vm.toggleDrums() })
                    Spacer(Modifier.width(6.dp))
                    VideoChip(videoMode, onClick = { if (!recording) toggleVideo() })
                },
                loopOn = song.loopOn,
                onLoop = { vm.toggleLoop(viewport.first, viewport.second) },
                onToStart = { vm.toStart() },
                onPlay = { vm.togglePlay() },
                onRecord = { if (recording) vm.stopRecording() else withMic { vm.recordPressed() } },
            )
        }
    }

    // ---------------------------------------------------------------- dialogs

    if (renaming) {
        RenameDialog(song.name, onDone = { vm.renameSong(song.id, it); renaming = false }, onDismiss = { renaming = false })
    }
    if (closePrompt) {
        AppDialog(
            onDismiss = { vm.dismissClosePrompt() },
            title = "Save changes to “${song.name}”?",
            body = "You changed this song since you opened it.",
        ) {
            DialogButton("Save", ButtonStyle.Primary) { vm.closeAndSave() }
            DialogButton("Save as new song", ButtonStyle.Plain) { vm.closeAndSaveAsNew() }
            DialogButton("Don't save", ButtonStyle.Danger) { vm.closeAndDiscard() }
            DialogButton("Cancel", ButtonStyle.Quiet) { vm.dismissClosePrompt() }
        }
    }
    if (bouncing) {
        BounceSheet(song, onBounce = { d, dr -> bouncing = false; vm.bounce(d, dr) }, onDismiss = { bouncing = false })
    }
    if (exporting) {
        ExportSheet(song, onExport = { k, save -> exporting = false; vm.export(k, save) }, onDismiss = { exporting = false })
    }
    if (syncSheet) {
        HeadphonesSheet(output, syncInfo, onSync = { syncSheet = false; withMic { vm.openCalibration() } }, onDismiss = { syncSheet = false })
    }
    prompt?.let { d ->
        SyncPromptDialog(d, onSync = { vm.syncFromPrompt() }, onSkip = { vm.skipSync() }, onDismiss = { vm.dismissSyncPrompt() })
    }
    cal?.let { st ->
        CalibrationDialog(st, output.name, position, onStart = { vm.runCalibration() }, onClose = { vm.closeCalibration(it) })
    }
    markerMenu?.let { id ->
        val m = song.markers.find { it.id == id }
        if (m == null) {
            LaunchedEffect(id) { markerMenu = null }
        } else {
            val bar = if (song.gridOn) {
                " · bar ${(m.frame / (Metronome.beatLength(song.bpm) * song.beatsPerBar)).toLong() + 1}"
            } else ""
            MarkerSheet(
                m,
                subtitle = "At ${formatTime(m.frame, SR)}$bar",
                onName = { n -> markerMenu = null; vm.renameMarker(id, n) },
                onCustom = { markerMenu = null; markerNaming = id },
                onLoop = { markerMenu = null; vm.loopSection(id) },
                onDelete = { markerMenu = null; vm.deleteMarker(id) },
                onDismiss = { markerMenu = null },
            )
        }
    }
    markerNaming?.let { id ->
        val m = song.markers.find { it.id == id }
        RenameDialog(
            if (m == null || m.name in SECTION_NAMES || m.name.length == 1) "" else m.name,
            onDone = { n -> markerNaming = null; if (n.isNotBlank()) vm.renameMarker(id, n) },
            onDismiss = { markerNaming = null },
            title = "Name this section",
            confirm = "Done",
        )
    }
    clipMenu?.let { c0 ->
        // For loop recordings, follow whichever pass is currently shown.
        // Always read the live take, so changes (volume, video) show up while the menu is open.
        val passes = song.passes(c0.group)
        val c = passes.firstOrNull { !it.hidden } ?: song.clips.find { it.id == c0.id } ?: c0
        TakeSheet(
            c,
            passes = passes,
            onPass = { vm.selectPass(it) },
            onToggleVideo = { vm.toggleVideoHidden(c.id) },
            onGain = { vm.setTakeGain(c.id, it) },
            onBoost = { vm.boostTake(c.id) },
            onLineUp = { clipMenu = null; vm.openNudge(c.id) },
            onDelete = { clipMenu = null; vm.deleteClip(c.id) },
            onDismiss = { clipMenu = null },
        )
    }
    if (trackMenu >= 0) {
        val t = trackMenu
        TrackSheet(
            t, song.clips.count { it.track == t },
            onClear = { trackMenu = -1; vm.clearTrack(t) },
            onDismiss = { trackMenu = -1 },
        )
    }
    if (toneFor >= 0) {
        val t = toneFor
        ChannelSheet(t, song.tracks[t], onChange = { f -> vm.setTrack(t, f) }, onDismiss = { toneFor = -1 })
    }
    if (tunerOpen) {
        TunerSheet(tunerReading, a4, onA4 = { vm.setA4(it) }, onDismiss = { vm.closeTuner() })
    }
    if (drumsOpen) {
        DrumSheet(
            song, position,
            onToggle = { vm.toggleDrums() },
            onChange = { vm.setDrums(it) },
            onPreset = { name -> vm.setDrums { vm.applyPreset(it, name) } },
            playing = playing,
            onPlay = { vm.togglePlay() },
            saved = savedPatterns,
            onSave = { vm.savePattern(it) },
            onUseSaved = { vm.useSavedPattern(it) },
            onDeleteSaved = { vm.deletePattern(it) },
            onDismiss = { drumsOpen = false },
        )
    }
    if (metronomeOpen) {
        MetronomeSheet(
            song, playing = playing, onPlay = { vm.togglePlay() },
            onChange = { vm.setMetronome(it) }, onDismiss = { metronomeOpen = false },
        )
    }
}

@Composable
private fun MetronomeChip(song: Song, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val on = song.click
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) Cream else Surface1)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("♩", color = if (on) Bg else Muted, fontSize = 15.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            "${song.bpm}",
            color = if (on) Bg else Cream, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun OutputChip(device: OutputDevice, info: SyncInfo?, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (device.kind) {
            OutputKind.BLUETOOTH -> Icons.Filled.Bluetooth
            OutputKind.WIRED -> Icons.Filled.Headphones
            OutputKind.SPEAKER -> Icons.Filled.Speaker
        }
        Icon(icon, contentDescription = null, tint = Cream, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(device.name, color = Cream, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        when {
            info?.synced == true -> {
                ColorDot(Good, 7)
                Spacer(Modifier.width(4.dp))
                Text("Synced", color = Good, fontSize = 12.sp)
            }

            device.kind == OutputKind.BLUETOOTH -> Text("Tap to sync", color = MuteAmber, fontSize = 12.sp)
            else -> Text("Auto", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TakeBar(
    clip: Clip,
    passes: List<Clip>,
    onLineUp: () -> Unit,
    onPass: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val track = clip.track
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(TrackColors[track], 10)
        Spacer(Modifier.width(8.dp))
        if (passes.size > 1) {
            val i = passes.indexOfFirst { it.id == clip.id }.coerceAtLeast(0)
            Text("Pass ${i + 1} of ${passes.size}", color = Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { onPass(passes[(i - 1 + passes.size) % passes.size].id) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous pass", tint = Cream)
            }
            IconButton(onClick = { onPass(passes[(i + 1) % passes.size].id) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next pass", tint = Cream)
            }
        } else {
            Text("New take on track ${track + 1}", color = Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onLineUp) { Text("Line up") }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Muted) }
    }
}

@Composable
private fun NudgePanel(
    n: NudgeState,
    song: Song,
    deviceName: String,
    onUndoTrim: () -> Unit,
    onNudge: (Long) -> Unit,
    onReset: () -> Unit,
    onRemember: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val clip = song.clips.find { it.id == n.clipId } ?: return
    val ms = clip.nudge * 1000f / SR
    val label = when {
        clip.nudge == 0L -> "As recorded"
        ms < 0 -> "%.1f ms earlier".format(-ms)
        else -> "%.1f ms later".format(ms)
    }
    val ms1 = SR / 1000L
    // Everything stays on one line so the panel always fits, Done included.
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(TrackColors[n.track], 10)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Line up & trim · track ${n.track + 1}", color = Cream, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(label, color = Cream, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1)
            }
            Text(
                "Drag to move · drag an edge to trim · pinch to zoom",
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NudgeButton("‹‹ 10", Modifier.weight(1f)) { onNudge(-10 * ms1) }
                NudgeButton("‹ 1", Modifier.weight(1f)) { onNudge(-ms1) }
                NudgeButton("1 ›", Modifier.weight(1f)) { onNudge(ms1) }
                NudgeButton("10 ››", Modifier.weight(1f)) { onNudge(10 * ms1) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("earlier (ms)", color = Faint, fontSize = 11.sp)
                Text("later (ms)", color = Faint, fontSize = 11.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRemember(!n.remember) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = n.remember, onCheckedChange = onRemember,
                    modifier = Modifier.size(36.dp),
                    colors = CheckboxDefaults.colors(checkedColor = Cream, checkmarkColor = Bg, uncheckedColor = Muted),
                )
                Text(
                    "Remember for $deviceName", color = Cream, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val trimmed = clip.start != n.openStart || clip.length != n.openLength
            TextButton(
                onClick = if (trimmed) onUndoTrim else onReset,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                Text(if (trimmed) "Undo trim" else "Reset", color = Muted, maxLines = 1)
            }
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Bg),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                modifier = Modifier.height(40.dp),
            ) {
                Text("Done", maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(label, color = Cream, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun Transport(
    position: () -> Long,
    playing: Boolean,
    recording: Boolean,
    countInTo: Long?,
    beatLength: Double,
    chips: @Composable () -> Unit,
    loopOn: Boolean,
    onLoop: () -> Unit,
    onToStart: () -> Unit,
    onPlay: () -> Unit,
    onRecord: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeText(position, recording, countInTo, beatLength, Modifier.weight(1f))
            chips()
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToStart, enabled = !recording, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Back to start", tint = if (recording) Faint else Cream, modifier = Modifier.size(30.dp))
            }
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (loopOn) Cream else Color.Transparent)
                    .clickable(onClick = onLoop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Repeat, contentDescription = "Loop", tint = if (loopOn) Bg else Cream, modifier = Modifier.size(26.dp))
            }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Surface2)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when {
                    recording -> Icons.Filled.Stop
                    playing -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                }
                Icon(icon, contentDescription = "Play", tint = Cream, modifier = Modifier.size(34.dp))
            }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(3.dp, Cream, CircleShape)
                    .clickable(onClick = onRecord),
                contentAlignment = Alignment.Center,
            ) {
                if (recording) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(RecRed))
                } else {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(RecRed))
                }
            }
        }
    }
}

@Composable
private fun VideoChip(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(top = 2.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(if (on) RecRed else Surface1)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Videocam, contentDescription = "Video", tint = Cream, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DrumsChip(song: Song, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val on = song.drumsOn
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) Cream else Surface1)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Bg else Faint))
        Spacer(Modifier.width(6.dp))
        Text("Drums", color = if (on) Bg else Cream, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TimeText(position: () -> Long, recording: Boolean, countInTo: Long?, beatLength: Double, modifier: Modifier) {
    val pos = position()
    val counting = countInTo != null && pos < countInTo
    Text(
        if (counting) "Count in ${kotlin.math.ceil((countInTo!! - pos) / beatLength).toInt().coerceAtLeast(1)}"
        else formatTime(pos, SR),
        color = if (recording) RecRed else Cream,
        fontFamily = FontFamily.Monospace,
        fontSize = 26.sp,
        modifier = modifier,
    )
}

