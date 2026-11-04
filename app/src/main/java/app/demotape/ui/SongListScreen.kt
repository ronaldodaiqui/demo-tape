package app.demotape.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.demotape.AppViewModel
import app.demotape.audio.SR
import app.demotape.data.Song
import app.demotape.data.TRACKS
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongListScreen(vm: AppViewModel, onRecordIdea: () -> Unit) {
    val songs by vm.songs.collectAsState()
    val previewing by vm.previewing.collectAsState()
    var listMenu by remember { mutableStateOf(false) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importSong(uri) }
    var notice by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.messages.collect {
            notice = it
            kotlinx.coroutines.delay(2200)
            notice = null
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui", 0) }
    var showIntro by remember { mutableStateOf(!prefs.getBoolean("intro_seen", false)) }
    var showAbout by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showTimed by remember { mutableStateOf(false) }
    // Tuner, also reachable from here (asks for the mic first if needed).
    val tunerOpen by vm.tunerOpen.collectAsState()
    val tunerReading by vm.tuner.reading.collectAsState()
    val a4 by vm.tunerA4.collectAsState()
    val micForTuner = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) vm.openTuner() else vm.micNeeded.value = true }
    val openTuner = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) vm.openTuner() else micForTuner.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    var byName by remember { mutableStateOf(prefs.getString("sort", "recent") == "name") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var keepersOnly by remember { mutableStateOf(prefs.getBoolean("keepers_only", false)) }
    val hasKeepers = songs.any { it.keeper }
    val shown = remember(songs, query, byName, keepersOnly, hasKeepers) {
        val q = query.trim()
        val k = if (keepersOnly && hasKeepers) songs.filter { it.keeper } else songs
        val f = if (q.isEmpty()) k else k.filter { it.name.contains(q, true) || it.notes.contains(q, true) }
        if (byName) f.sortedBy { it.name.lowercase() } else f.sortedByDescending { it.modified }
    }
    var menuFor by remember { mutableStateOf<Song?>(null) }
    var renameFor by remember { mutableStateOf<Song?>(null) }
    var deleteFor by remember { mutableStateOf<Song?>(null) }

    if (showHelp) {
        androidx.activity.compose.BackHandler { showHelp = false }
        HelpScreen(onBack = { showHelp = false }, onIntro = { showHelp = false; showIntro = true })
        if (showIntro) {
            IntroDialog(onDone = { showIntro = false })
        }
        return
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searching) {
                            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                            androidx.compose.foundation.text.BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Cream, fontSize = 18.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Cream),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                decorationBox = { inner ->
                                    if (query.isEmpty()) Text("Search songs and notes", color = Faint, fontSize = 18.sp)
                                    inner()
                                },
                            )
                            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
                        } else {
                            Text("Demo-tape", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Cream),
                    actions = {
                        androidx.compose.material3.IconButton(onClick = { openTuner() }) {
                            Icon(TuningFork, contentDescription = "Tuner", tint = Cream)
                        }
                        if (songs.isNotEmpty() || searching) {
                            androidx.compose.material3.IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                                Icon(
                                    if (searching) androidx.compose.material.icons.Icons.Filled.Close else androidx.compose.material.icons.Icons.Filled.Search,
                                    contentDescription = if (searching) "Close search" else "Search", tint = Cream,
                                )
                            }
                        }
                        Box {
                            androidx.compose.material3.IconButton(onClick = { listMenu = true }) {
                                Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = "More", tint = Cream)
                            }
                            AppMenu(expanded = listMenu, onDismiss = { listMenu = false }) {
                                AppMenuItem(
                                    if (byName) "Sort by most recent" else "Sort by name",
                                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.Sort,
                                ) {
                                    listMenu = false
                                    byName = !byName
                                    prefs.edit().putString("sort", if (byName) "name" else "recent").apply()
                                }
                                AppMenuItem("Timed song…", androidx.compose.material.icons.Icons.Filled.Timer) {
                                    listMenu = false
                                    showTimed = true
                                }
                                AppMenuItem("Import song", androidx.compose.material.icons.Icons.Filled.FileDownload) {
                                    listMenu = false
                                    importer.launch(arrayOf("*/*"))
                                }
                                AppMenuItem("Help", androidx.compose.material.icons.Icons.AutoMirrored.Filled.HelpOutline) {
                                    listMenu = false; showHelp = true
                                }
                                AppMenuItem("About", androidx.compose.material.icons.Icons.Filled.Info) {
                                    listMenu = false; showAbout = true
                                }
                            }
                        }
                    },
                )
                androidx.compose.animation.AnimatedVisibility(visible = notice != null) {
                    var shown by remember { mutableStateOf("") }
                    notice?.let { shown = it }
                    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                        Text(
                            shown, color = Cream, fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Surface2)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExtendedFloatingActionButton(
                    onClick = { vm.newSong() },
                    containerColor = Cream,
                    contentColor = Bg,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New song") },
                )
                Spacer(Modifier.width(12.dp))
                // Record a new idea right now: new song, track 1, already rolling.
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Surface2)
                        .clickable(onClick = onRecordIdea),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(RecRed))
                }
            }
        },
    ) { pad ->
        if (songs.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TrackColors.forEach {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(it))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("No songs yet", color = Cream, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Start one and lay down the first track.", color = Muted)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ★ Keepers filter, once anything is marked.
                if (hasKeepers) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip("All", !keepersOnly) {
                                keepersOnly = false
                                prefs.edit().putBoolean("keepers_only", false).apply()
                            }
                            FilterChip("★ Keepers", keepersOnly) {
                                keepersOnly = true
                                prefs.edit().putBoolean("keepers_only", true).apply()
                            }
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            "Nothing matches “${query.trim()}”", color = Muted,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                items(shown, key = { it.id }) { s ->
                    SongRow(
                        s,
                        Modifier.combinedClickable(
                            onClick = { vm.openSong(s.id) },
                            onLongClick = { menuFor = s },
                        ),
                        overview = { vm.overview(it) },
                        playing = previewing == s.id,
                        position = { vm.engine.position.value },
                        onPlay = { vm.togglePreview(s) },
                        onSeek = { f -> vm.previewFrom(s, f) },
                        onKeeper = { vm.toggleKeeper(s.id) },
                    )
                }
            }
        }
    }

    if (showIntro) {
        IntroDialog(onDone = {
            showIntro = false
            prefs.edit().putBoolean("intro_seen", true).apply()
        })
    }
    if (tunerOpen) {
        TunerSheet(tunerReading, a4, onA4 = { vm.setA4(it) }, onDismiss = { vm.closeTuner() })
    }
    if (showTimed) {
        TimedSongSheet(
            onStart = { m -> showTimed = false; vm.startTimedSong(m) },
            onDismiss = { showTimed = false },
        )
    }
    if (showAbout) {
        AboutSheet(onHelp = { showAbout = false; showHelp = true }, onDismiss = { showAbout = false })
    }
    menuFor?.let { s ->
        SongSheet(
            s,
            onRename = { menuFor = null; renameFor = s },
            onDelete = { menuFor = null; vm.deleteSong(s.id) },
            onDismiss = { menuFor = null },
        )
    }
    renameFor?.let { s ->
        RenameDialog(s.name, onDone = { vm.renameSong(s.id, it); renameFor = null }, onDismiss = { renameFor = null })
    }
    deleteFor?.let { s ->
        ConfirmDialog(
            title = "Delete “${s.name}”?",
            body = "All of its takes will be gone for good.",
            confirm = "Delete",
            onConfirm = { vm.deleteSong(s.id); deleteFor = null },
            onDismiss = { deleteFor = null },
        )
    }
}

@Composable
private fun SongRow(
    s: Song,
    modifier: Modifier,
    overview: suspend (Song) -> app.demotape.SongOverview?,
    playing: Boolean,
    position: () -> Long,
    onPlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onKeeper: () -> Unit,
) {
    val date = remember(s.modified) { SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(s.modified)) }
    val o by androidx.compose.runtime.produceState<app.demotape.SongOverview?>(null, s.id, s.modified) { value = overview(s) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                // The song that's playing gets a faint wash of its color.
                o?.takeIf { playing }?.let { lerp(Surface1, TrackColors[it.mainTrack], 0.1f) } ?: Surface1
            )
            .padding(start = 10.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Listen from the list: plays from where the sound starts. Colored like the song's
        // main track: soft when idle, solid while playing.
        val canPlay = o != null
        val accent = o?.let { TrackColors[it.mainTrack] } ?: Faint
        Box(
            Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    when {
                        playing -> accent
                        canPlay -> accent.copy(alpha = 0.18f)
                        else -> Surface2
                    }
                )
                .clickable(enabled = canPlay, onClick = onPlay)
                .semantics { contentDescription = if (playing) "Stop" else "Play ${s.name}" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) androidx.compose.material.icons.Icons.Filled.Stop else androidx.compose.material.icons.Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = when {
                    playing -> Bg
                    canPlay -> accent
                    else -> Faint
                },
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                // Up to two lines, so a long name (or big system text) still shows the time.
                Text(
                    s.name, color = Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Keeper star: tap to mark the good ones.
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(28.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable(onClick = onKeeper)
                        .semantics { contentDescription = if (s.keeper) "Keeper, tap to unmark" else "Mark as keeper" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (s.keeper) androidx.compose.material.icons.Icons.Filled.Star else androidx.compose.material.icons.Icons.Outlined.StarOutline,
                        contentDescription = null,
                        tint = if (s.keeper) MuteAmber else Faint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            val len = o?.let { it.end - it.start } ?: s.length
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$date · ${formatLength(len, SR)}", color = Muted, fontSize = 13.sp)
                // Which tracks have something on them, in their colors (a bit tighter on small phones).
                val small = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360
                Spacer(Modifier.width(if (small) 5.dp else 8.dp))
                for (t in 0 until TRACKS) {
                    Box(
                        Modifier
                            .padding(end = if (small) 3.dp else 4.dp)
                            .size(if (small) 6.dp else 7.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (s.hasAudio(t)) TrackColors[t] else Surface2)
                    )
                }
            }
            val firstLine = remember(s.notes) { s.notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } }
            if (firstLine != null) {
                Text(
                    firstLine, color = Faint, fontSize = 13.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // Narrower on small phones, leaving room for the name.
        val waveW = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360) 56.dp else 84.dp
        MiniWave(o, playing, position, onSeek, Modifier.width(waveW).height(34.dp))
    }
}

/** The song's sound in a few dozen bars; while it plays, what's been heard lights up. */
@Composable
private fun MiniWave(
    o: app.demotape.SongOverview?,
    playing: Boolean,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    // Redraw every frame while playing, so the progress glides.
    var tick by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(playing) {
        while (playing) androidx.compose.runtime.withFrameNanos { tick = it }
    }
    // Tap to play from that spot; drag to scrub (it jumps there when you let go).
    var scrub by remember { mutableStateOf<Float?>(null) }
    val oNow by androidx.compose.runtime.rememberUpdatedState(o)
    val seekNow by androidx.compose.runtime.rememberUpdatedState(onSeek)
    fun frameAt(frac: Float): Long? = oNow?.let { it.start + ((it.end - it.start) * frac.coerceIn(0f, 1f)).toLong() }
    androidx.compose.foundation.Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { p -> frameAt(p.x / size.width)?.let(seekNow) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { p -> if (oNow != null) scrub = p.x / size.width },
                    onDragEnd = { scrub?.let { f -> frameAt(f)?.let(seekNow) }; scrub = null },
                    onDragCancel = { scrub = null },
                ) { ch, _ ->
                    ch.consume()
                    if (scrub != null) scrub = (ch.position.x / size.width).coerceIn(0f, 1f)
                }
            }
    ) {
        tick
        if (o == null) {
            drawLine(Surface2, androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                androidx.compose.ui.geometry.Offset(size.width, size.height / 2), 1.dp.toPx())
            return@Canvas
        }
        val n = o.bars.size
        val step = size.width / n
        val w = (step * 0.6f).coerceAtLeast(1f)
        val sc = scrub
        val showing = playing || sc != null
        val progress = sc ?: if (playing) ((position() - o.start).toFloat() / (o.end - o.start).coerceAtLeast(1L)).coerceIn(0f, 1f) else 0f
        for (i in 0 until n) {
            val h = (o.bars[i] * size.height).coerceAtLeast(1.5.dp.toPx())
            val x = i * step + (step - w) / 2
            val heard = showing && (i + 0.5f) / n <= progress
            // In the song's main track color; while playing, what's still to come is dimmed
            // and what you've heard is bright.
            val c = TrackColors[o.mainTrack]
            drawRoundRect(
                when {
                    heard -> c
                    showing -> c.copy(alpha = 0.3f)
                    else -> c.copy(alpha = 0.8f)
                },
                androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2),
                androidx.compose.ui.geometry.Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(w / 2),
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Cream else Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Bg else Cream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
