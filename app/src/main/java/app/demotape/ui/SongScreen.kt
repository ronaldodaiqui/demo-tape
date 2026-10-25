package app.demotape.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

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

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var syncSheet by remember { mutableStateOf(false) }
    var clipMenu by remember { mutableStateOf<Clip?>(null) }
    var trackMenu by remember { mutableStateOf(-1) }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Cream),
                navigationIcon = {
                    IconButton(onClick = { vm.closeSong() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Songs", tint = Cream)
                    }
                },
                title = {
                    Text(
                        song.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = !recording) { renaming = true },
                    )
                },
                actions = {
                    IconButton(onClick = { vm.undoLastTake() }, enabled = song.clips.isNotEmpty() && !recording) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo last take",
                            tint = if (song.clips.isNotEmpty() && !recording) Cream else Faint,
                        )
                    }
                    IconButton(onClick = { exporting = true }, enabled = song.clips.isNotEmpty() && !recording) {
                        Icon(
                            Icons.Filled.IosShare, contentDescription = "Export",
                            tint = if (song.clips.isNotEmpty() && !recording) Cream else Faint,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Cream)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                            DropdownMenuItem(text = { Text("Sync check") }, onClick = {
                                menuOpen = false
                                withMic { vm.openCalibration() }
                            })
                            DropdownMenuItem(text = { Text("Delete song", color = RecRed) }, onClick = {
                                menuOpen = false
                                deleting = true
                            })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutputChip(output, syncInfo, onClick = { if (!recording) syncSheet = true })

            Timeline(
                song = song,
                peaks = vm.peaks,
                position = position,
                follow = playing && nudge == null,
                live = live,
                nudgeClip = nudge?.clipId,
                onSeek = { vm.seek(it) },
                onLongPressClip = { if (!recording) clipMenu = it },
                onNudgeDrag = { vm.nudgeBy(it) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            val n = nudge
            val lt = lastTake
            if (n != null) {
                NudgePanel(n, song, output.name, onNudge = { vm.nudgeBy(it) }, onReset = { vm.resetNudge() },
                    onRemember = { vm.setRemember(it) }, onDone = { vm.closeNudge() })
            } else if (lt != null && !recording) {
                val track = song.clips.find { it.id == lt }?.track
                if (track != null) {
                    TakeBar(track, onLineUp = { vm.openNudge(lt) }, onDismiss = { vm.dismissTake() })
                }
            }

            Row(
                Modifier.fillMaxWidth().height(230.dp).padding(horizontal = 8.dp, vertical = 10.dp),
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
                        onVolume = { vm.setVolume(t, it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Transport(
                position = position,
                playing = playing,
                recording = recording,
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
    if (deleting) {
        ConfirmDialog(
            "Delete “${song.name}”?", "All of its takes will be gone for good.", "Delete",
            onConfirm = { deleting = false; vm.deleteSong(song.id) }, onDismiss = { deleting = false },
        )
    }
    if (exporting) {
        ExportDialog(onPick = { f, save -> exporting = false; vm.export(f, save) }, onDismiss = { exporting = false })
    }
    if (syncSheet) {
        SyncInfoDialog(output, syncInfo, onSync = { syncSheet = false; withMic { vm.openCalibration() } }, onDismiss = { syncSheet = false })
    }
    prompt?.let { d ->
        SyncPromptDialog(d, onSync = { vm.syncFromPrompt() }, onSkip = { vm.skipSync() }, onDismiss = { vm.dismissSyncPrompt() })
    }
    cal?.let { st ->
        CalibrationDialog(st, output.name, position, onStart = { vm.runCalibration() }, onClose = { vm.closeCalibration(it) })
    }
    clipMenu?.let { c ->
        AlertDialog(
            onDismissRequest = { clipMenu = null },
            title = { Text("Take on track ${c.track + 1}") },
            text = {
                Column {
                    TextButton(onClick = { clipMenu = null; vm.openNudge(c.id) }) { Text("Line up (nudge timing)") }
                    TextButton(onClick = { clipMenu = null; vm.deleteClip(c.id) }) { Text("Delete this take", color = RecRed) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { clipMenu = null }) { Text("Close") } },
        )
    }
    if (trackMenu >= 0) {
        val t = trackMenu
        ConfirmDialog(
            "Clear track ${t + 1}?", "Every take on this track will be deleted.", "Clear",
            onConfirm = { trackMenu = -1; vm.clearTrack(t) }, onDismiss = { trackMenu = -1 },
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
private fun TakeBar(track: Int, onLineUp: () -> Unit, onDismiss: () -> Unit) {
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
        Text("New take on track ${track + 1}", color = Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onLineUp) { Text("Line up") }
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Muted) }
    }
}

@Composable
private fun NudgePanel(
    n: NudgeState,
    song: Song,
    deviceName: String,
    onNudge: (Long) -> Unit,
    onReset: () -> Unit,
    onRemember: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val clip = song.clips.find { it.id == n.clipId } ?: return
    val ms = clip.nudge * 1000 / SR
    val label = when {
        ms == 0L -> "As recorded"
        ms < 0 -> "${abs(ms)} ms earlier"
        else -> "$ms ms later"
    }
    val ms1 = SR / 1000L
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(TrackColors[n.track], 10)
            Spacer(Modifier.width(8.dp))
            Text("Line up take", color = Cream, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(label, color = Cream, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
        Text(
            "Looping. Drag the timeline, or tap below, until it sits right.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NudgeButton("‹‹ 10", Modifier.weight(1f)) { onNudge(-10 * ms1) }
            NudgeButton("‹ 1", Modifier.weight(1f)) { onNudge(-ms1) }
            NudgeButton("1 ›", Modifier.weight(1f)) { onNudge(ms1) }
            NudgeButton("10 ››", Modifier.weight(1f)) { onNudge(10 * ms1) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("earlier", color = Faint, fontSize = 11.sp)
            Text("later", color = Faint, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = n.remember, onCheckedChange = onRemember,
                colors = CheckboxDefaults.colors(checkedColor = Cream, checkmarkColor = Bg, uncheckedColor = Muted),
            )
            Text(
                "Use for future takes on $deviceName", color = Cream, fontSize = 13.sp,
                modifier = Modifier.weight(1f), maxLines = 2,
            )
            TextButton(onClick = onReset) { Text("Reset", color = Muted) }
            Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Bg)) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(label, color = Cream, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun Transport(
    position: () -> Long,
    playing: Boolean,
    recording: Boolean,
    onToStart: () -> Unit,
    onPlay: () -> Unit,
    onRecord: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeText(position, recording, Modifier.weight(1f))
        IconButton(onClick = onToStart, enabled = !recording, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Back to start", tint = if (recording) Faint else Cream, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(60.dp)
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
            Icon(icon, contentDescription = "Play", tint = Cream, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .border(3.dp, Cream, CircleShape)
                .clickable(onClick = onRecord),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(RecRed))
            } else {
                Box(Modifier.size(48.dp).clip(CircleShape).background(RecRed))
            }
        }
    }
}

@Composable
private fun TimeText(position: () -> Long, recording: Boolean, modifier: Modifier) {
    Text(
        formatTime(position(), SR),
        color = if (recording) RecRed else Cream,
        fontFamily = FontFamily.Monospace,
        fontSize = 28.sp,
        modifier = modifier,
    )
}

