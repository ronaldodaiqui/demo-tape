package app.demotape.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Four lanes of waveforms with a ruler and playhead.
 * Tap to move the playhead, drag to scroll, pinch to zoom, long-press a take for options.
 * While a take is being lined up ([nudgeClip]), a one-finger drag that starts on that
 * take's track slides the take instead of scrolling.
 */
@Composable
fun Timeline(
    song: Song,
    peaks: Map<String, ByteArray>,
    samples: (String) -> ShortBuffer?,
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

    // When lining up a take that's off screen, scroll to its start (zoom stays yours).
    LaunchedEffect(nudgeClip) {
        val c = nudgeClip?.let { id -> songNow.clips.find { it.id == id } } ?: return@LaunchedEffect
        val sec = c.pos / SR.toFloat()
        val vis = widthPx / pps
        if (sec < viewStart || sec > viewStart + vis * 0.9f) viewStart = max(0f, sec - vis * 0.15f)
    }

    Canvas(
        modifier
            .onSizeChanged { widthPx = max(1, it.width) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o -> seekCb(((viewStart + o.x / pps) * SR).toLong().coerceAtLeast(0L)) },
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
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val laneH = (size.height - rulerH) / TRACKS
                    val downLane = floor((down.position.y - rulerH) / laneH).toInt()
                    val target = nudgeNow?.let { id -> songNow.clips.find { it.id == id } }
                    val grabTake = target != null && downLane == target.track
                    val minPps = 2f
                    val maxPps = size.width / 0.04f // 40 ms across the screen
                    var moving = false
                    var travel = 0f
                    var multi = false
                    var acc = 0.0
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed > 1) multi = true
                        val zoom = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        if (!moving) {
                            travel += pan.getDistance()
                            if (travel > viewConfiguration.touchSlop || zoom != 1f) moving = true else continue
                        }
                        if (grabTake && !multi) {
                            acc += pan.x / pps * SR.toDouble()
                            val whole = acc.toLong()
                            if (whole != 0L) {
                                nudgeCb(whole)
                                acc -= whole
                            }
                        } else {
                            val c = ev.calculateCentroid(useCurrent = false)
                            val cx = if (c.isSpecified) c.x else 0f
                            val anchor = viewStart + cx / pps
                            pps = (pps * zoom).coerceIn(minPps, max(minPps, maxPps))
                            viewStart = max(0f, anchor - cx / pps - pan.x / pps)
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            }
    ) {
        val laneH = (size.height - rulerH) / TRACKS
        val startF = viewStart.toDouble() * SR
        val pxPerFrame = pps.toDouble() / SR

        // lanes
        for (t in 0 until TRACKS) {
            drawRect(if (t % 2 == 0) LaneA else LaneB, Offset(0f, rulerH + t * laneH), Size(size.width, laneH))
        }
        nudgeClip?.let { id ->
            song.clips.find { it.id == id }?.let { c ->
                drawRect(TrackColors[c.track].copy(alpha = 0.07f), Offset(0f, rulerH + c.track * laneH), Size(size.width, laneH))
            }
        }

        // takes, oldest first so newer takes cover what they replace
        for (c in song.clips) {
            val muted = song.tracks[c.track].muted
            drawTake(
                pos = c.pos, length = c.length, track = c.track, peaks = peaks[c.id], samples = samples(c.id),
                color = TrackColors[c.track].let { if (muted) it.copy(alpha = 0.35f) else it },
                highlight = c.id == nudgeClip, rulerH = rulerH, laneH = laneH,
                startF = startF, pxPerFrame = pxPerFrame,
            )
        }
        live?.let { lt ->
            drawTake(
                pos = lt.from, length = lt.peaks.size.toLong() * PEAK_HOP, track = lt.track, peaks = lt.peaks,
                samples = null, color = RecRed, highlight = false, rulerH = rulerH, laneH = laneH,
                startF = startF, pxPerFrame = pxPerFrame,
            )
        }

        // track numbers. Measure first, then draw: drawing straight from the measurer
        // crashes when text starts at or past the canvas edge.
        for (t in 0 until TRACKS) {
            drawText(
                tm.measure("${t + 1}", TextStyle(color = TrackColors[t].copy(alpha = 0.8f), fontSize = 11.sp)),
                topLeft = Offset(6.dp.toPx(), rulerH + t * laneH + 4.dp.toPx()),
            )
        }

        // ruler
        drawRect(Bg, Offset.Zero, Size(size.width, rulerH))
        val steps = floatArrayOf(
            0.005f, 0.01f, 0.025f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f,
        )
        val iv = steps.firstOrNull { it * pps >= 70.dp.toPx() } ?: 600f
        var i = floor(viewStart / iv).toLong()
        val endSec = viewStart + size.width / pps
        while (i * iv <= endSec) {
            val t = i * iv
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
            i++
        }

        if (song.clips.isEmpty() && live == null) {
            val layout = tm.measure("Tap a track number, then press record", TextStyle(color = Muted, fontSize = 14.sp))
            drawText(
                layout,
                topLeft = Offset(
                    (size.width - layout.size.width) / 2f,
                    rulerH + (size.height - rulerH - layout.size.height) / 2f,
                ),
            )
        }

        // playhead
        val px = ((position() - startF) * pxPerFrame).toFloat()
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
    val totalMs = (sec * 1000f + 0.5f).toLong()
    val s = totalMs / 1000
    return if (iv >= 1f) "%d:%02d".format(s / 60, s % 60)
    else "%d:%02d.%03d".format(s / 60, s % 60, totalMs % 1000)
}

private fun DrawScope.drawTake(
    pos: Long,
    length: Long,
    track: Int,
    peaks: ByteArray?,
    samples: ShortBuffer?,
    color: Color,
    highlight: Boolean,
    rulerH: Float,
    laneH: Float,
    startF: Double,
    pxPerFrame: Double,
) {
    val pad = 3.dp.toPx()
    val top = rulerH + track * laneH + pad
    val h = laneH - pad * 2
    val mid = top + h / 2
    val x0 = ((pos - startF) * pxPerFrame).toFloat().coerceAtLeast(0f)
    val x1 = ((pos + length - startF) * pxPerFrame).toFloat().coerceAtMost(size.width)
    if (x1 <= x0) return
    val laneBg = if (track % 2 == 0) LaneA else LaneB
    drawRect(lerp(laneBg, color, 0.16f), Offset(x0, top), Size(x1 - x0, h))

    val step = 2f
    val framesPerCol = step / pxPerFrame
    val half = h * 0.46f
    if (samples != null && framesPerCol < PEAK_HOP * 2) {
        // Zoomed in: draw the actual samples (min/max per column), so transients line up exactly.
        drawLine(color.copy(alpha = 0.35f), Offset(x0, mid), Offset(x1, mid), 1f)
        val cap = min(samples.capacity().toLong(), length)
        var x = floor(x0)
        while (x < x1) {
            val a = (startF + x / pxPerFrame - pos).toLong().coerceAtLeast(0L)
            val b = (startF + (x + step) / pxPerFrame - pos).toLong().coerceAtMost(cap - 1)
            if (a >= cap) break
            var lo = 0
            var hi = 0
            var f = a
            while (f <= b) {
                val v = samples.get(f.toInt()).toInt()
                if (v < lo) lo = v
                if (v > hi) hi = v
                f++
            }
            val yHi = mid - shape(hi) * half
            val yLo = mid - shape(lo) * half
            drawLine(color, Offset(x + 1f, yHi), Offset(x + 1f, max(yLo, yHi + 1f)), step * 0.75f)
            x += step
        }
    } else if (peaks != null && peaks.isNotEmpty()) {
        var x = floor(x0)
        while (x < x1) {
            val iA = ((startF + x / pxPerFrame - pos) / PEAK_HOP).toInt().coerceAtLeast(0)
            if (iA >= peaks.size) break
            val iB = ((startF + (x + step) / pxPerFrame - pos) / PEAK_HOP).toInt().coerceIn(iA, peaks.size - 1)
            var m = 0
            for (i in iA..iB) {
                val v = peaks[i].toInt() and 0xff
                if (v > m) m = v
            }
            val a = max(0.5f, sqrt(m / 255f) * half)
            drawLine(color, Offset(x + 1f, mid - a), Offset(x + 1f, mid + a), step * 0.75f)
            x += step
        }
    }
    drawRect(
        if (highlight) Cream else color.copy(alpha = 0.55f), Offset(x0, top), Size(x1 - x0, h),
        style = Stroke(width = if (highlight) 2.dp.toPx() else 1.dp.toPx()),
    )
}

/** Signed sample to -1..1 with the same square-root scaling as the overview, so both views match. */
private fun shape(v: Int): Float {
    val a = sqrt(abs(v) / 32768f)
    return if (v < 0) -a else a
}
