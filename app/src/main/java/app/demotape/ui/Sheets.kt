package app.demotape.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.demotape.audio.SR
import app.demotape.data.Clip
import app.demotape.data.Song
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Shared look for the app's bottom sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        contentColor = Cream,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Faint) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
        ) { content() }
    }
}

/** Header with a colored badge: "2" in track 2's color, for example. */
@Composable
fun SheetHeader(badge: String, color: Color, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(badge, color = color, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Cream, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun SheetAction(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tint: Color = Cream,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

/** Long-press menu for a take on the timeline. Delete needs a second tap. */
@Composable
fun TakeSheet(clip: Clip, onLineUp: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val time = remember(clip.created) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(clip.created))
    }
    AppSheet(onDismiss) {
        SheetHeader(
            "${clip.track + 1}", TrackColors[clip.track],
            "Take on track ${clip.track + 1}",
            "Starts ${formatTime(clip.pos, SR)} · ${formatLength(clip.length, SR)} long · recorded $time",
        )
        SheetAction(Icons.Filled.SwapHoriz, "Line up", "Slide this take earlier or later", onClick = onLineUp)
        SheetAction(
            Icons.Filled.Delete,
            if (confirm) "Tap again to delete" else "Delete take",
            if (confirm) "This can't be undone" else null,
            tint = RecRed,
        ) { if (confirm) onDelete() else confirm = true }
    }
}

/** Long-press menu for a track number. */
@Composable
fun TrackSheet(track: Int, takes: Int, onClear: () -> Unit, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    AppSheet(onDismiss) {
        SheetHeader("${track + 1}", TrackColors[track], "Track ${track + 1}", if (takes == 1) "1 take" else "$takes takes")
        SheetAction(
            Icons.Filled.DeleteSweep,
            if (confirm) "Tap again to clear" else "Clear track",
            if (confirm) "Every take on this track will be deleted" else "Delete every take on this track",
            tint = RecRed,
        ) { if (confirm) onClear() else confirm = true }
    }
}

/** Long-press menu for a song in the list. */
@Composable
fun SongSheet(song: Song, onRename: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    AppSheet(onDismiss) {
        SheetHeader("♪", Cream, song.name, formatLength(song.length, SR))
        SheetAction(Icons.Filled.Edit, "Rename", null, onClick = onRename)
        SheetAction(
            Icons.Filled.Delete,
            if (confirm) "Tap again to delete" else "Delete song",
            if (confirm) "All of its takes will be gone for good" else null,
            tint = RecRed,
        ) { if (confirm) onDelete() else confirm = true }
    }
}

@Composable
fun MetronomeSheet(song: Song, onChange: ((Song) -> Song) -> Unit, onDismiss: () -> Unit) {
    val taps = remember { ArrayList<Long>() }
    AppSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Metronome", color = Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(
                checked = song.click,
                onCheckedChange = { on -> onChange { it.copy(click = on) } },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Bg, checkedTrackColor = Cream,
                    uncheckedThumbColor = Muted, uncheckedTrackColor = Surface2, uncheckedBorderColor = Faint,
                ),
            )
        }
        Text(
            "Plays in your headphones only. It's never recorded or exported.",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(18.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundButton("−") { onChange { it.copy(bpm = (it.bpm - 1).coerceAtLeast(30)) } }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${song.bpm}", color = Cream, fontSize = 56.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace)
                Text("BPM", color = Muted, fontSize = 12.sp, letterSpacing = 3.sp)
            }
            RoundButton("+") { onChange { it.copy(bpm = (it.bpm + 1).coerceAtMost(300)) } }
        }
        Slider(
            value = song.bpm.toFloat().coerceIn(40f, 240f),
            onValueChange = { v -> onChange { it.copy(bpm = v.roundToInt()) } },
            valueRange = 40f..240f,
            colors = SliderDefaults.colors(
                thumbColor = Cream, activeTrackColor = Cream, inactiveTrackColor = Surface2,
            ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
                .clickable {
                    val now = SystemClock.uptimeMillis()
                    if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                    taps.add(now)
                    while (taps.size > 6) taps.removeAt(0)
                    if (taps.size >= 2) {
                        val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
                        val bpm = (60000.0 / avg).roundToInt().coerceIn(30, 300)
                        onChange { it.copy(bpm = bpm) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("Tap tempo", color = Cream, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(20.dp))
        Text("Beats per bar", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in listOf(2, 3, 4, 5, 6, 7)) {
                val sel = song.beatsPerBar == b
                Box(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) Cream else Surface2)
                        .clickable { onChange { it.copy(beatsPerBar = b) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$b", color = if (sel) Bg else Cream, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Count in", color = Cream, fontSize = 16.sp)
                Text("One bar of clicks before recording starts", color = Muted, fontSize = 13.sp)
            }
            Switch(
                checked = song.countIn,
                onCheckedChange = { on -> onChange { it.copy(countIn = on) } },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Bg, checkedTrackColor = Cream,
                    uncheckedThumbColor = Muted, uncheckedTrackColor = Surface2, uncheckedBorderColor = Faint,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", color = Cream, fontSize = 16.sp, modifier = Modifier.width(80.dp))
            Slider(
                value = song.clickVolume,
                onValueChange = { v -> onChange { it.copy(clickVolume = v) } },
                valueRange = 0.05f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Cream, activeTrackColor = Cream, inactiveTrackColor = Surface2,
                ),
            )
        }
    }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Cream, fontSize = 28.sp, fontWeight = FontWeight.Light)
    }
}
