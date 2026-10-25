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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                .combinedClickable(onClick = onArm, onLongClick = onArmLong),
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
        Box(
            Modifier
                .width(44.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state.muted) MuteAmber else Surface2)
                .clickable(onClick = onMute),
            contentAlignment = Alignment.Center,
        ) {
            Text("M", color = if (state.muted) Bg else Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
