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
import androidx.compose.material.icons.filled.MoreVert
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
    var menuFor by remember { mutableStateOf<Song?>(null) }
    var renameFor by remember { mutableStateOf<Song?>(null) }
    var deleteFor by remember { mutableStateOf<Song?>(null) }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Demo-tape", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Cream),
                    actions = {
                        Box {
                            androidx.compose.material3.IconButton(onClick = { listMenu = true }) {
                                Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = "More", tint = Cream)
                            }
                            androidx.compose.material3.DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Import song") },
                                    onClick = {
                                        listMenu = false
                                        importer.launch(arrayOf("*/*"))
                                    },
                                )
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
                items(songs, key = { it.id }) { s ->
                    SongRow(
                        s,
                        Modifier.combinedClickable(
                            onClick = { vm.openSong(s.id) },
                            onLongClick = { menuFor = s },
                        )
                    )
                }
            }
        }
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
private fun SongRow(s: Song, modifier: Modifier) {
    val date = remember(s.modified) { SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(s.modified)) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name, color = Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("$date · ${formatLength(s.length, SR)}", color = Muted, fontSize = 13.sp)
            val firstLine = remember(s.notes) { s.notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } }
            if (firstLine != null) {
                Text(
                    firstLine, color = Faint, fontSize = 13.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (t in 0 until TRACKS) {
                Box(
                    Modifier
                        .width(6.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (s.hasAudio(t)) TrackColors[t] else Surface2)
                )
            }
        }
    }
}
