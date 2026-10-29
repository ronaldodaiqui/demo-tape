package app.demotape.ui

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
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
import app.demotape.audio.SR
import app.demotape.data.OutputDevice
import kotlin.math.exp

/** How a dialog button looks: the main choice, an ordinary one, a destructive one, or a quiet way out. */
enum class ButtonStyle { Primary, Plain, Danger, Record, Quiet }

/**
 * The app's dialog: a warm dark card with a title, an optional line of explanation, and
 * full-width rounded buttons stacked underneath (same look as the sheets).
 */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    body: String? = null,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = Surface1, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp)) {
                Text(title, color = Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                if (body != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(body, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(18.dp))
                content()
            }
        }
    }
}

@Composable
fun DialogButton(label: String, style: ButtonStyle = ButtonStyle.Plain, onClick: () -> Unit) {
    val (bg, fg) = when (style) {
        ButtonStyle.Primary -> Cream to Bg
        ButtonStyle.Record -> RecRed to Cream
        ButtonStyle.Danger -> Surface2 to RecRed
        ButtonStyle.Quiet -> Color.Transparent to Muted
        ButtonStyle.Plain -> Surface2 to Cream
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(if (style == ButtonStyle.Quiet) 42.dp else 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** The ⋮ menus: rounded, warm, with an icon per item. */
@Composable
fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Surface2,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, Faint.copy(alpha = 0.35f)),
        content = content,
    )
}

@Composable
fun AppMenuItem(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (enabled) Cream else Faint, fontSize = 15.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (enabled) Muted else Faint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

@Composable
fun RenameDialog(
    initial: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Rename",
    confirm: String = "Save",
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AppDialog(onDismiss, title) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(color = Cream, fontSize = 17.sp),
            cursorBrush = SolidColor(Cream),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Bg)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                ) { inner() }
            },
        )
        Spacer(Modifier.height(14.dp))
        DialogButton(confirm, ButtonStyle.Primary) { onDone(value.text) }
        DialogButton("Cancel", ButtonStyle.Quiet, onDismiss)
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(onDismiss, title, body) {
        DialogButton(confirm, ButtonStyle.Danger, onConfirm)
        DialogButton("Cancel", ButtonStyle.Quiet, onDismiss)
    }
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
    AppDialog(
        onDismiss,
        "New headphones: ${device.name}",
        "Bluetooth adds a delay. A quick 5-second sync makes your overdubs land right on time with these headphones from now on.",
    ) {
        DialogButton("Sync", ButtonStyle.Primary, onSync)
        DialogButton("Skip", ButtonStyle.Quiet, onSkip)
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
                        DialogButton("Start", ButtonStyle.Primary, onStart)
                        DialogButton("Cancel", ButtonStyle.Quiet) { onClose(false) }
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
                            DialogButton("Start recording", ButtonStyle.Record) { onClose(true) }
                            DialogButton("Not yet", ButtonStyle.Plain) { onClose(false) }
                        } else {
                            DialogButton("Done", ButtonStyle.Primary) { onClose(false) }
                        }
                        DialogButton("Try again", ButtonStyle.Quiet, onStart)
                    }

                    is CalState.Failed -> {
                        Text("Let's try that again", color = Cream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        Text(state.reason, color = Cream, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        DialogButton("Try again", ButtonStyle.Primary, onStart)
                        DialogButton("Cancel", ButtonStyle.Quiet) { onClose(false) }
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
