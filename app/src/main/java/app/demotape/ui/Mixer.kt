package app.demotape.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.demotape.data.TrackState
import kotlin.math.sqrt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelStrip(
    track: Int,
    state: TrackState,
    armed: Boolean,
    recording: Boolean,
    level: () -> Float,
    onArm: () -> Unit,
    onArmLong: () -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onTone: () -> Unit,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = TrackColors[track]
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        val a = if (recording) {
            val pulse = rememberInfiniteTransition(label = "rec")
            pulse.animateFloat(
                initialValue = 0.45f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "recAlpha",
            ).value
        } else 1f
        val bg = when {
            recording -> RecRed.copy(alpha = a)
            armed -> RecRed
            else -> Color.Transparent
        }
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(bg)
                .border(2.dp, if (armed) RecRed else color, CircleShape)
                .combinedClickable(onClick = onArm, onLongClick = onArmLong)
                .semantics {
                    contentDescription = "Track ${track + 1}, " + when {
                        recording -> "recording"
                        armed -> "armed"
                        else -> "tap to arm for recording"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${track + 1}",
                color = if (armed) Cream else color,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Fader(
            value = state.volume,
            onChange = onVolume,
            color = color,
            level = if (armed) level else null,
            modifier = Modifier.weight(1f).width(60.dp),
        )
        Spacer(Modifier.height(8.dp))
        // M, S and tone share the row so they fit on narrow phones too.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            SmallButton(on = state.muted, onColor = MuteAmber, onClick = onMute, modifier = Modifier.weight(1f).semantics { contentDescription = "Mute track ${track + 1}" }) {
                Text("M", color = if (state.muted) Bg else Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            SmallButton(on = state.solo, onColor = Good, onClick = onSolo, modifier = Modifier.weight(1f).semantics { contentDescription = "Solo track ${track + 1}" }) {
                Text("S", color = if (state.solo) Bg else Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            // Tone & pan. Lit in the track color when anything is set away from flat/center.
            val lit = state.toneOrPan
            SmallButton(on = lit, onColor = color.copy(alpha = 0.25f), onClick = onTone, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Tune, contentDescription = "Tone and pan", tint = if (lit) color else Muted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Vertical fader. 0.8 is unity gain (tick mark); double-tap resets to it. */
@Composable
fun Fader(
    value: Float,
    onChange: (Float) -> Unit,
    color: Color,
    level: (() -> Float)?,
    modifier: Modifier = Modifier,
) {
    val v by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { cb(0.8f) }) }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    val travel = size.height - 18.dp.toPx()
                    if (travel > 0) cb((v - dy / travel).coerceIn(0f, 1f))
                }
            }
    ) {
        val knobH = 18.dp.toPx()
        val cx = size.width / 2
        val travel = size.height - knobH
        val top = knobH / 2
        val slotW = 4.dp.toPx()
        drawRoundRect(Surface2, Offset(cx - slotW / 2, top), Size(slotW, travel), CornerRadius(slotW / 2))
        val ky = (1f - value) * travel
        drawRoundRect(
            color.copy(alpha = 0.6f), Offset(cx - slotW / 2, top + ky), Size(slotW, travel - ky), CornerRadius(slotW / 2)
        )
        val uy = top + 0.2f * travel
        drawLine(Faint, Offset(cx - 18.dp.toPx(), uy), Offset(cx - 10.dp.toPx(), uy), 1.5.dp.toPx())
        if (level != null) {
            val l = level().coerceIn(0f, 1f)
            val lh = sqrt(l) * travel
            val mc = when {
                l > 0.97f -> RecRed
                l > 0.5f -> MuteAmber
                else -> Good
            }
            drawRoundRect(Surface2, Offset(cx + 12.dp.toPx(), top), Size(4.dp.toPx(), travel), CornerRadius(2.dp.toPx()))
            drawRoundRect(mc, Offset(cx + 12.dp.toPx(), top + travel - lh), Size(4.dp.toPx(), lh), CornerRadius(2.dp.toPx()))
        }
        val kw = 34.dp.toPx()
        drawRoundRect(Cream, Offset(cx - kw / 2, ky), Size(kw, knobH), CornerRadius(4.dp.toPx()))
        drawLine(color, Offset(cx - kw / 2 + 6.dp.toPx(), ky + knobH / 2), Offset(cx + kw / 2 - 6.dp.toPx(), ky + knobH / 2), 2.5.dp.toPx())
    }
}

/**
 * Bipolar horizontal slider: -1..1, centered at 0 (with a little stick there). Touch or drag
 * anywhere along it; double-tap to reset. [label] on the left, [valueText] on the right.
 * Styled like the EQ sliders.
 */
@Composable
fun BipolarSlider(
    value: Float,
    onChange: (Float) -> Unit,
    color: Color,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
) {
    val cb by rememberUpdatedState(onChange)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = if (value != 0f) Cream else Faint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics { contentDescription = "$label, $valueText" }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { cb(0f) }) }
                .pointerInput(Unit) {
                    val pad = 12.dp.toPx()
                    fun at(x: Float): Float {
                        val v = ((x - pad) / (size.width - pad * 2) * 2f - 1f).coerceIn(-1f, 1f)
                        return if (kotlin.math.abs(v) < 0.04f) 0f else v
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { p -> cb(at(p.x)) },
                    ) { ch, _ ->
                        ch.consume()
                        cb(at(ch.position.x))
                    }
                },
        ) {
            val pad = 12.dp.toPx()
            val cy = size.height / 2
            val slot = 4.dp.toPx()
            val w = size.width - pad * 2
            val cx = pad + w / 2
            drawRoundRect(Surface2, Offset(pad, cy - slot / 2), Size(w, slot), CornerRadius(slot / 2))
            drawLine(Faint, Offset(cx, cy - 7.dp.toPx()), Offset(cx, cy + 7.dp.toPx()), 1.5.dp.toPx())
            val x = cx + value.coerceIn(-1f, 1f) * w / 2
            if (value != 0f) {
                drawRoundRect(
                    color.copy(alpha = 0.45f), Offset(minOf(x, cx), cy - slot / 2), Size(kotlin.math.abs(x - cx), slot),
                    CornerRadius(slot / 2),
                )
            }
            val r = 9.dp.toPx()
            drawCircle(if (value != 0f) color else Muted, r, Offset(x, cy))
            drawCircle(Bg, r * 0.38f, Offset(x, cy))
        }
    }
}

@Composable
private fun SmallButton(
    on: Boolean,
    onColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) onColor else Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
