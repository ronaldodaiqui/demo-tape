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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
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
fun TakeSheet(
    clip: Clip,
    passes: List<Clip>,
    onPass: (String) -> Unit,
    onLineUp: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
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
        if (passes.size > 1) {
            val i = passes.indexOfFirst { it.id == clip.id }.coerceAtLeast(0)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface2)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PassArrow("‹") { onPass(passes[(i - 1 + passes.size) % passes.size].id) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pass ${i + 1} of ${passes.size}", color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Loop recording · pick the keeper", color = Muted, fontSize = 12.sp)
                }
                PassArrow("›") { onPass(passes[(i + 1) % passes.size].id) }
            }
        }
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

/** Which headphones are connected and how they're synced. Reached from the ⋮ menu. */
@Composable
fun HeadphonesSheet(
    device: app.demotape.data.OutputDevice,
    info: app.demotape.data.SyncInfo?,
    onSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    val icon = when (device.kind) {
        app.demotape.data.OutputKind.BLUETOOTH -> Icons.Filled.Bluetooth
        app.demotape.data.OutputKind.WIRED -> Icons.Filled.Headphones
        app.demotape.data.OutputKind.SPEAKER -> Icons.Filled.Speaker
    }
    val synced = info?.synced == true
    AppSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Cream, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Listening on", color = Muted, fontSize = 13.sp)
                Text(device.name, color = Cream, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (synced) Good else MuteAmber))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (synced) "Synced" else "Not synced yet",
                    color = Cream, fontWeight = FontWeight.Medium,
                )
                Text(
                    if (synced) "Round-trip delay about ${info!!.delayMs} ms, removed from every take automatically."
                    else "Takes are lined up from the delay Android reports, which is usually close. A sync check makes it exact.",
                    color = Muted, fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SheetAction(
            Icons.Filled.GraphicEq,
            if (synced) "Sync again" else "Run sync check",
            "5 seconds: knock on the phone along with 8 clicks",
            onClick = onSync,
        )
        Text(
            "Each pair of headphones remembers its own sync. Recording always uses the phone's mic.",
            color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A channel's tone (low / mid / high) and pan, like a Portastudio channel strip. */
@Composable
fun ChannelSheet(
    track: Int,
    state: app.demotape.data.TrackState,
    onChange: ((app.demotape.data.TrackState) -> app.demotape.data.TrackState) -> Unit,
    onDismiss: () -> Unit,
) {
    val color = TrackColors[track]
    fun db(v: Float) = when {
        v == 0f -> "0 dB"
        v > 0 -> "+%.1f dB".format(v)
        else -> "%.1f dB".format(v)
    }
    fun pan(v: Float): String {
        val p = (kotlin.math.abs(v) * 100).roundToInt()
        return when {
            p == 0 -> "C"
            v < 0 -> "L $p"
            else -> "R $p"
        }
    }
    AppSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                SheetHeader("${track + 1}", color, "Track ${track + 1}", "Tone & pan")
            }
            if (state.toneOrPan) {
                Text(
                    "Reset", color = Muted, fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onChange { it.copy(low = 0f, mid = 0f, high = 0f, pan = 0f, midFreq = 1000f) } }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Mid frequency knob: center is 1 kHz, full left 200 Hz, full right 5 kHz.
        val freqKnob = (kotlin.math.ln(state.midFreq / 1000f) / kotlin.math.ln(5f)).coerceIn(-1f, 1f)
        fun hz(f: Float) = if (f >= 1000f) "%.1f kHz".format(f / 1000f) else "${f.roundToInt()} Hz"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Knob(state.low / 15f, { v -> onChange { it.copy(low = v * 15f) } }, color, "Low", db(state.low))
            Knob(state.mid / 15f, { v -> onChange { it.copy(mid = v * 15f) } }, color, "Mid", db(state.mid))
            Knob(freqKnob, { v -> onChange { it.copy(midFreq = 1000f * Math.pow(5.0, v.toDouble()).toFloat()) } }, color, "Mid freq", hz(state.midFreq))
            Knob(state.high / 15f, { v -> onChange { it.copy(high = v * 15f) } }, color, "High", db(state.high))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Knob(state.pan, { v -> onChange { it.copy(pan = v) } }, color, "Pan", pan(state.pan))
        }
        Text(
            "Drag a knob up or down. Double-tap to reset it.",
            color = Faint, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

/** Chromatic tuner: note name, a cents needle (green when within 3 cents), and frequency. */
@Composable
fun TunerSheet(
    reading: app.demotape.audio.TunerReading?,
    a4: Float,
    onA4: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val cents = reading?.cents ?: 0f
    val needle by androidx.compose.animation.core.animateFloatAsState(
        targetValue = cents.coerceIn(-50f, 50f),
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "needle",
    )
    val inTune = reading != null && kotlin.math.abs(cents) < 3f
    val accent = when {
        reading == null -> Faint
        inTune -> Good
        kotlin.math.abs(cents) < 15f -> MuteAmber
        else -> RecRed
    }
    AppSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tuner", color = Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("A4", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            SmallStep("−") { onA4(a4 - 1f) }
            Text(
                "${a4.roundToInt()} Hz", color = Cream, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            SmallStep("+") { onA4(a4 + 1f) }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                reading?.note ?: "–",
                color = if (reading == null) Faint else if (inTune) Good else Cream,
                fontSize = 96.sp, fontWeight = FontWeight.Light,
            )
            if (reading != null) {
                Text(
                    "${reading.octave}", color = Muted, fontSize = 24.sp,
                    modifier = Modifier.padding(bottom = 18.dp, start = 2.dp),
                )
            }
        }
        // cents meter
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val w = size.width
            val mid = w / 2
            val baseY = size.height * 0.7f
            for (c in -50..50 step 5) {
                val x = mid + c / 50f * (w / 2 - 12.dp.toPx())
                val big = c % 25 == 0
                drawLine(
                    if (c == 0) Cream else Faint,
                    androidx.compose.ui.geometry.Offset(x, baseY - (if (big) 18.dp.toPx() else 9.dp.toPx())),
                    androidx.compose.ui.geometry.Offset(x, baseY),
                    if (c == 0) 2.5.dp.toPx() else 1.dp.toPx(),
                )
            }
            // in-tune zone
            val zone = 3f / 50f * (w / 2 - 12.dp.toPx())
            drawRect(
                Good.copy(alpha = 0.18f),
                androidx.compose.ui.geometry.Offset(mid - zone, baseY - 24.dp.toPx()),
                androidx.compose.ui.geometry.Size(zone * 2, 28.dp.toPx()),
            )
            if (reading != null) {
                val x = mid + needle / 50f * (w / 2 - 12.dp.toPx())
                drawLine(
                    accent,
                    androidx.compose.ui.geometry.Offset(x, 4.dp.toPx()),
                    androidx.compose.ui.geometry.Offset(x, size.height - 4.dp.toPx()),
                    4.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("♭ flat", color = Faint, fontSize = 12.sp)
            Text("sharp ♯", color = Faint, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                reading == null -> "Play a note"
                inTune -> "In tune · %.1f Hz".format(reading.hz)
                else -> "%+.0f cents · %.1f Hz".format(cents, reading.hz)
            },
            color = if (reading == null) Muted else Cream,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            "Uses the phone's mic. Hold it near the instrument.",
            color = Faint, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SmallStep(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Cream, fontSize = 18.sp)
    }
}

@Composable
private fun PassArrow(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Cream, fontSize = 30.sp, fontWeight = FontWeight.Light)
    }
}
