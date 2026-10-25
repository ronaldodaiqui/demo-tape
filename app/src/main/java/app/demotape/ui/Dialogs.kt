package app.demotape.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.demotape.CalState
import app.demotape.audio.Calibration
import app.demotape.audio.Exporter
import app.demotape.audio.SR
import app.demotape.data.OutputDevice
import app.demotape.data.SyncInfo
import kotlin.math.exp

@Composable
fun RenameDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = RecRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BusyDialog(label: String) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(20.dp), color = Surface1) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Cream, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(label, color = Cream)
            }
        }
    }
}

@Composable
fun SyncPromptDialog(device: OutputDevice, onSync: () -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New headphones: ${device.name}") },
        text = {
            Text("Bluetooth adds a delay. A quick 5-second sync makes your overdubs land right on time with these headphones from now on.")
        },
        confirmButton = { TextButton(onClick = onSync) { Text("Sync") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}

@Composable
fun SyncInfoDialog(device: OutputDevice, info: SyncInfo?, onSync: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.name) },
        text = {
            Text(
                if (info?.synced == true)
                    "Synced. Round-trip delay is about ${info.delayMs} ms, and new takes are shifted back by that automatically."
                else
                    "Not synced yet. Takes are lined up using the delay Android reports, which is usually close. A quick sync check makes it exact."
            )
        },
        confirmButton = { TextButton(onClick = onSync) { Text(if (info?.synced == true) "Sync again" else "Sync now") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun ExportDialog(onPick: (Exporter.Format, Boolean) -> Unit, onDismiss: () -> Unit) {
    val canSave = Build.VERSION.SDK_INT >= 29
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export mixdown") },
        text = {
            Column {
                Text("M4A is small and plays anywhere. WAV is full quality.", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                ExportRow("M4A", canSave, onShare = { onPick(Exporter.Format.M4A, false) }, onSave = { onPick(Exporter.Format.M4A, true) })
                ExportRow("WAV", canSave, onShare = { onPick(Exporter.Format.WAV, false) }, onSave = { onPick(Exporter.Format.WAV, true) })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ExportRow(label: String, canSave: Boolean, onShare: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Cream, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onShare) { Text("Share") }
        if (canSave) TextButton(onClick = onSave) { Text("Save to Downloads") }
    }
}

/** The tap-along sync check. [position] reads the song frame currently heard. */
@Composable
fun CalibrationDialog(
    state: CalState,
    deviceName: String,
    position: () -> Long,
    onStart: () -> Unit,
    onClose: (thenRecord: Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = { if (state !is CalState.Running) onClose(false) },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = Surface1) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    is CalState.Intro -> {
                        Text("Sync check", color = Cream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Put on $deviceName. You'll hear ${Calibration.COUNT} clicks. Knock on the phone with a finger in time with them.",
                            color = Cream, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Bg)) {
                            Text("Start")
                        }
                        TextButton(onClick = { onClose(false) }) { Text("Cancel", color = Muted) }
                    }

                    is CalState.Running -> {
                        Text("Knock along", color = Cream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(20.dp))
                        BeatPulse(state.clicks, position)
                        Spacer(Modifier.height(12.dp))
                        Text("Keep going until the clicks stop", color = Muted)
                    }

                    is CalState.Done -> {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Good, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Synced", color = Cream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "$deviceName delay is about ${state.delayMs} ms. Takes recorded with these headphones will line up automatically.",
                            color = Cream, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        if (state.thenRecord != null) {
                            Button(
                                onClick = { onClose(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = RecRed, contentColor = Cream),
                            ) { Text("Start recording") }
                            TextButton(onClick = { onClose(false) }) { Text("Not yet", color = Muted) }
                        } else {
                            Button(onClick = { onClose(false) }, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Bg)) {
                                Text("Done")
                            }
                        }
                        TextButton(onClick = onStart) { Text("Try again", color = Muted) }
                    }

                    is CalState.Failed -> {
                        Text("Let's try that again", color = Cream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        Text(state.reason, color = Cream, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Bg)) {
                            Text("Try again")
                        }
                        TextButton(onClick = { onClose(false) }) { Text("Cancel", color = Muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeatPulse(clicks: LongArray, position: () -> Long) {
    Canvas(Modifier.size(160.dp)) {
        val pos = position()
        val last = clicks.lastOrNull { it <= pos }
        val pulse = if (last == null) 0f else exp(-(pos - last) / (SR * 0.12f))
        val r = size.minDimension / 2
        drawCircle(Surface2, radius = r * 0.55f)
        drawCircle(Cream.copy(alpha = 0.25f + 0.75f * pulse), radius = r * (0.55f + 0.35f * pulse))
        drawCircle(Cream, radius = r * 0.92f, style = Stroke(width = 2.dp.toPx()))
        // progress dots
        val heard = clicks.count { it <= pos }
        val n = clicks.size
        for (i in 0 until n) {
            val a = Math.PI * 2 * i / n - Math.PI / 2
            val c = Offset(
                center.x + (r * 0.92f * kotlin.math.cos(a)).toFloat(),
                center.y + (r * 0.92f * kotlin.math.sin(a)).toFloat(),
            )
            drawCircle(if (i < heard) Cream else Faint, radius = 5.dp.toPx(), center = c)
        }
    }
}

@Composable
fun ColorDot(color: androidx.compose.ui.graphics.Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}
