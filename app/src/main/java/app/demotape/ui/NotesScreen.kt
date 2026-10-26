package app.demotape.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.demotape.audio.SR
import app.demotape.data.Song
import app.demotape.data.TRACKS

/** Notes icon with a small dot when the song has notes. */
@Composable
fun BadgedNotesIcon(hasNotes: Boolean) {
    Box {
        Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Notes", tint = Cream)
        if (hasNotes) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MuteAmber)
            )
        }
    }
}

/**
 * A song's notes: track names up top, then free text (lyrics, chords, tuning, reminders) in a
 * monospace font so chords stay lined up over words. A mini transport at the bottom lets you
 * read lyrics while recording.
 */
@Composable
fun NotesScreen(
    song: Song,
    position: () -> Long,
    playing: Boolean,
    recording: Boolean,
    armed: Int,
    onNotes: (String) -> Unit,
    onTrackName: (Int, String) -> Unit,
    onPlay: () -> Unit,
    onRecord: () -> Unit,
    onClose: () -> Unit,
) {
    // Local copy so typing stays snappy; every change is also saved to the song.
    var text by remember(song.id) { mutableStateOf(song.notes) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Cream)
            }
            Column(Modifier.weight(1f)) {
                Text("Notes", color = Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(song.name, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Track names
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until TRACKS / 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in 0..1) {
                        val t = row * 2 + c
                        TrackNameField(t, song.tracks[t].name, { onTrackName(t, it) }, Modifier.weight(1f))
                    }
                }
            }
        }

        // Notes
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface1)
                .padding(14.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onNotes(it)
                },
                textStyle = TextStyle(color = Cream, fontSize = 15.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
                cursorBrush = SolidColor(Cream),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            "Lyrics, chords, tuning, capo…\n\nG        C        G\nWords go under the chords",
                            color = Faint, fontSize = 15.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp,
                        )
                    }
                    inner()
                },
            )
        }

        // Mini transport: read the lyrics while you record.
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatTime(position(), SR),
                    color = if (recording) RecRed else Cream,
                    fontFamily = FontFamily.Monospace, fontSize = 22.sp,
                )
                if (armed >= 0) {
                    Text(
                        "Track ${armed + 1}" + song.tracks[armed].name.let { if (it.isBlank()) "" else " · $it" },
                        color = TrackColors[armed], fontSize = 12.sp,
                    )
                }
            }
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(Surface2).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when {
                    recording -> Icons.Filled.Stop
                    playing -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                }
                Icon(icon, contentDescription = "Play", tint = Cream, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.size(60.dp).clip(CircleShape).border(3.dp, Cream, CircleShape).clickable(onClick = onRecord),
                contentAlignment = Alignment.Center,
            ) {
                if (recording) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(RecRed))
                } else {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(RecRed))
                }
            }
        }
    }
}

@Composable
private fun TrackNameField(track: Int, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    var text by remember(track) { mutableStateOf(value) }
    val color = TrackColors[track]
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("${track + 1}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = text,
            onValueChange = {
                text = it.take(16).replace("\n", "")
                onChange(text)
            },
            singleLine = true,
            textStyle = TextStyle(color = Cream, fontSize = 15.sp),
            cursorBrush = SolidColor(Cream),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("Name", color = Faint, fontSize = 15.sp)
                inner()
            },
        )
    }
}
