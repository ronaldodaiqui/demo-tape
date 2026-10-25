package app.demotape.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.demotape.LiveTake
import app.demotape.audio.PEAK_HOP
import app.demotape.audio.SR
import app.demotape.data.Clip
import app.demotape.data.Song
import app.demotape.data.TRACKS
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Four lanes of waveforms with a ruler and playhead. Tap to move the playhead, pinch to
 * zoom, drag to scroll, long-press a take for its options. While a take is being lined up
 * ([nudgeClip]), dragging shifts that take instead of scrolling.
 */
@Composable
fun Timeline(
    song: Song,
    peaks: Map<String, ByteArray>,
    position: () -> Long,
    follow: Boolean,
    live: LiveTake?,
    nudgeClip: String?,
    onSeek: (Long) -> Unit,
    onLongPressClip: (Clip) -> Unit,
    onNudgeDrag: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var pps by remember { mutableFloatStateOf(with(density) { 56.dp.toPx() }) } // px per second
    var viewStart by remember { mutableFloatStateOf(0f) } // seconds
    var widthPx by remember { mutableIntStateOf(1) }
    var saved by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val tm = rememberTextMeasurer()
    val rulerH = with(density) { 22.dp.toPx() }

    val songNow by rememberUpdatedState(song)
    val nudgeNow by rememberUpdatedState(nudgeClip)
    val seekCb by rememberUpdatedState(onSeek)
    val longCb by rememberUpdatedState(onLongPressClip)
    val nudgeCb by rememberUpdatedState(onNudgeDrag)
    val followNow by rememberUpdatedState(follow)
    val posNow by rememberUpdatedState(position)

    // Keep the playhead in view while playing.
    LaunchedEffect(Unit) {
        snapshotFlow { if (followNow) posNow() else -1L }.collect { p ->
            if (p < 0) return@collect
            val sec = p / SR.toFloat()
            val vis = widthPx / pps
            if (sec < viewStart || sec > viewStart + vis * 0.85f) viewStart = max(0f, sec - vis * 0.2f)
        }
    }

    // Zoom in on the take being lined up; restore the view afterwards.
    LaunchedEffect(nudgeClip) {
        if (nudgeClip != null) {
            val c = songNow.clips.find { it.id == nudgeClip } ?: return@LaunchedEffect
            if (saved == null) saved = pps to viewStart
            pps = widthPx / 3f
            viewStart = max(0f, c.pos / SR.toFloat() - 0.4f)
        } else {
            saved?.let {
                pps = it.first
                viewStart = it.second
            }
            saved = null
        }
    }

    Canvas(
        modifier
            .onSizeChanged { widthPx = max(1, it.width) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o ->
                        if (nudgeNow == null) seekCb(((viewStart + o.x / pps) * SR).toLong().coerceAtLeast(0L))
                    },
                    onLongPress = { o ->
                        val laneH = (size.height - rulerH) / TRACKS
                        val lane = floor((o.y - rulerH) / laneH).toInt()
                        if (lane in 0 until TRACKS) {
                            val f = ((viewStart + o.x / pps) * SR).toLong()
                            songNow.clips
                                .filter { it.track == lane && f >= it.pos && f < it.end }
                                .maxByOrNull { it.created }
                                ?.let { longCb(it) }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                var acc = 0f
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (nudgeNow != null) {
                        acc += pan.x / pps * SR
                        val whole = acc.toLong()
                        if (whole != 0L) {
                            nudgeCb(whole)
                            acc -= whole
                        }
                    } else {
                        val anchor = viewStart + centroid.x / pps
                        pps = (pps * zoom).coerceIn(2f, size.width / 0.5f)
                        viewStart = max(0f, anchor - centroid.x / pps - pan.x / pps)
                    }
                }
            }
    ) {
        val laneH = (size.height - rulerH) / TRACKS
        val startF = viewStart * SR
        val pxPerFrame = pps / SR

        fun xOf(frame: Long): Float = ((frame - startF) * pxPerFrame)

        // lanes
        for (t in 0 until TRACKS) {
            drawRect(if (t % 2 == 0) LaneA else LaneB, Offset(0f, rulerH + t * laneH), Size(size.width, laneH))
        }

        // takes, oldest first so newer takes cover what they replace
        for (c in song.clips) {
            val muted = song.tracks[c.track].muted
            drawTake(
                pos = c.pos, length = c.length, track = c.track, peaks = peaks[c.id],
                color = TrackColors[c.track].let { if (muted) it.copy(alpha = 0.35f) else it },
                highlight = c.id == nudgeClip, rulerH = rulerH, laneH = laneH, xOf = ::xOf,
                startF = startF, pxPerFrame = pxPerFrame,
            )
        }
        live?.let { lt ->
            drawTake(
                pos = lt.from, length = lt.peaks.size.toLong() * PEAK_HOP, track = lt.track, peaks = lt.peaks,
                color = RecRed, highlight = false, rulerH = rulerH, laneH = laneH, xOf = ::xOf,
                startF = startF, pxPerFrame = pxPerFrame,
            )
        }

        // track numbers
        for (t in 0 until TRACKS) {
            // Measure first, then draw: drawing straight from the measurer crashes when the
            // text starts at or past the canvas edge.
            drawText(
                tm.measure("${t + 1}", TextStyle(color = TrackColors[t].copy(alpha = 0.8f), fontSize = 11.sp)),
                topLeft = Offset(6.dp.toPx(), rulerH + t * laneH + 4.dp.toPx()),
            )
        }

        // ruler
        drawRect(Bg, Offset.Zero, Size(size.width, rulerH))
        val steps = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f)
        val minGap = 64.dp.toPx()
        val iv = steps.firstOrNull { it * pps >= minGap } ?: 600f
        var t = floor(viewStart / iv) * iv
        val endSec = viewStart + size.width / pps
        while (t <= endSec) {
            val x = (t - viewStart) * pps
            if (x >= 0) {
                drawLine(Faint, Offset(x, rulerH - 6.dp.toPx()), Offset(x, rulerH), 1.dp.toPx())
                drawLine(Faint.copy(alpha = 0.25f), Offset(x, rulerH), Offset(x, size.height), 1f)
                if (x + 3.dp.toPx() < size.width) {
                    drawText(
                        tm.measure(rulerLabel(t, iv), TextStyle(color = Muted, fontSize = 10.sp)),
                        topLeft = Offset(x + 3.dp.toPx(), 3.dp.toPx()),
                    )
                }
            }
            t += iv
        }

        if (song.clips.isEmpty() && live == null) {
            val layout = tm.measure(
                "Tap a track number, then press record",
                TextStyle(color = Muted, fontSize = 14.sp),
            )
            drawText(
                layout,
                topLeft = Offset(
                    (size.width - layout.size.width) / 2f,
                    rulerH + (size.height - rulerH - layout.size.height) / 2f,
                ),
            )
        }

        // playhead
        val px = xOf(position())
        if (px >= 0 && px <= size.width) {
            drawLine(Cream, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
            val tri = Path().apply {
                moveTo(px - 6.dp.toPx(), 0f)
                lineTo(px + 6.dp.toPx(), 0f)
                lineTo(px, 8.dp.toPx())
                close()
            }
            drawPath(tri, Cream)
        }
    }
}

private fun rulerLabel(sec: Float, iv: Float): String {
    val s = sec.toInt()
    return if (iv < 1f) {
        "%d:%02d.%d".format(s / 60, s % 60, ((sec - s) * 10 + 0.5f).toInt().coerceAtMost(9))
    } else "%d:%02d".format(s / 60, s % 60)
}

private fun DrawScope.drawTake(
    pos: Long,
    length: Long,
    track: Int,
    peaks: ByteArray?,
    color: Color,
    highlight: Boolean,
    rulerH: Float,
    laneH: Float,
    xOf: (Long) -> Float,
    startF: Float,
    pxPerFrame: Float,
) {
    val pad = 3.dp.toPx()
    val top = rulerH + track * laneH + pad
    val h = laneH - pad * 2
    val mid = top + h / 2
    val x0 = xOf(pos).coerceAtLeast(0f)
    val x1 = xOf(pos + length).coerceAtMost(size.width)
    if (x1 <= x0) return
    val laneBg = if (track % 2 == 0) LaneA else LaneB
    drawRect(lerp(laneBg, color, 0.16f), Offset(x0, top), Size(x1 - x0, h))
    if (peaks != null && peaks.isNotEmpty()) {
        val step = 2f
        var x = floor(x0)
        while (x < x1) {
            val fA = startF + x / pxPerFrame
            val fB = startF + (x + step) / pxPerFrame
            val iA = ((fA - pos) / PEAK_HOP).toInt().coerceAtLeast(0)
            if (iA >= peaks.size) break
            val iB = ((fB - pos) / PEAK_HOP).toInt().coerceIn(iA, peaks.size - 1)
            var m = 0
            for (i in iA..iB) {
                val v = peaks[i].toInt() and 0xff
                if (v > m) m = v
            }
            val a = max(0.5f, sqrt(m / 255f) * h * 0.46f)
            drawLine(color, Offset(x + 1f, mid - a), Offset(x + 1f, mid + a), step * 0.75f)
            x += step
        }
    }
    drawRect(
        if (highlight) Cream else color.copy(alpha = 0.55f), Offset(x0, top), Size(x1 - x0, h),
        style = Stroke(width = if (highlight) 2.dp.toPx() else 1.dp.toPx()),
    )
}
