package app.demotape.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.demotape.audio.GraphicEq
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 8-band graphic EQ: a slider per band with the overall curve drawn through them.
 * Drag a band up or down, or sweep a finger across to draw a shape. Double-tap a band to
 * flatten it. Moves in half-dB steps, with a little stick at 0.
 */
@Composable
fun EqPanel(gains: List<Float>, color: Color, onChange: (List<Float>) -> Unit, modifier: Modifier = Modifier) {
    val n = GraphicEq.BANDS
    val range = GraphicEq.RANGE
    val now by rememberUpdatedState(gains)
    val cb by rememberUpdatedState(onChange)
    var active by remember { mutableStateOf<Int?>(null) }

    fun bandAt(x: Float, w: Float) = (x / w * n).toInt().coerceIn(0, n - 1)
    fun gainAt(y: Float, h: Float, pad: Float): Float {
        val v = ((h / 2 - y) / (h / 2 - pad) * range).coerceIn(-range, range)
        val q = (v * 2).roundToInt() / 2f
        return if (abs(q) < 0.75f) 0f else q
    }
    fun set(i: Int, g: Float) {
        val cur = now
        if (cur.getOrNull(i) != g) cb(List(n) { b -> if (b == i) g else cur.getOrNull(b) ?: 0f })
    }

    Column(modifier) {
        // The band being moved, and its value.
        val a = active
        Text(
            if (a == null) " " else "${GraphicEq.LABELS[a]} Hz   ${db(gains.getOrNull(a) ?: 0f)}",
            color = if (a == null) Faint else Cream, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            textAlign = TextAlign.Center,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .semantics { contentDescription = "Equalizer, 8 bands" }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { p -> set(bandAt(p.x, size.width.toFloat()), 0f) })
                }
                .pointerInput(Unit) {
                    val pad = 10.dp.toPx()
                    detectDragGestures(
                        onDragStart = { p ->
                            val i = bandAt(p.x, size.width.toFloat())
                            active = i
                            set(i, gainAt(p.y, size.height.toFloat(), pad))
                        },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { ch, _ ->
                        ch.consume()
                        val i = bandAt(ch.position.x, size.width.toFloat())
                        active = i
                        set(i, gainAt(ch.position.y, size.height.toFloat(), pad))
                    }
                },
        ) {
            val pad = 10.dp.toPx()
            val w = size.width
            val h = size.height
            val colW = w / n
            fun yOf(db: Float) = h / 2 - db / range * (h / 2 - pad)

            // Guides at 0 and ±6 dB.
            drawLine(Faint.copy(alpha = 0.5f), Offset(0f, yOf(0f)), Offset(w, yOf(0f)), 1.dp.toPx())
            for (g in listOf(6f, -6f)) {
                drawLine(Surface2, Offset(0f, yOf(g)), Offset(w, yOf(g)), 1.dp.toPx())
            }

            // Slider slots and thumbs.
            for (i in 0 until n) {
                val cx = colW * (i + 0.5f)
                val slotW = 4.dp.toPx()
                drawRoundRect(Surface2, Offset(cx - slotW / 2, pad), Size(slotW, h - pad * 2), CornerRadius(slotW / 2))
                val g = gains.getOrNull(i) ?: 0f
                val y = yOf(g)
                if (g != 0f) {
                    val top = minOf(y, yOf(0f))
                    drawRoundRect(color.copy(alpha = 0.45f), Offset(cx - slotW / 2, top), Size(slotW, abs(y - yOf(0f))), CornerRadius(slotW / 2))
                }
                val r = if (active == i) 11.dp.toPx() else 9.dp.toPx()
                drawCircle(if (g != 0f || active == i) color else Muted, r, Offset(cx, y))
                drawCircle(Bg, r * 0.38f, Offset(cx, y))
            }

            // The curve you'll hear: band i sits at the middle of column i, an octave apart.
            if (gains.any { it != 0f }) {
                val path = Path()
                val steps = 120
                for (k in 0..steps) {
                    val x = w * k / steps
                    val octaves = x / colW - 0.5
                    val f = GraphicEq.FREQS[0] * 2.0.pow(octaves)
                    val y = yOf(GraphicEq.responseDb(gains, f).toFloat().coerceIn(-range * 1.2f, range * 1.2f))
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Cream.copy(alpha = 0.85f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (i in 0 until n) {
                Text(
                    GraphicEq.LABELS[i], color = if ((gains.getOrNull(i) ?: 0f) != 0f) Cream else Faint,
                    fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun db(v: Float) = when {
    v == 0f -> "0 dB"
    v > 0 -> "+%.1f dB".format(v)
    else -> "%.1f dB".format(v)
}

@Suppress("unused")
private fun octaveOf(f: Double) = log2(f / GraphicEq.FREQS[0])
