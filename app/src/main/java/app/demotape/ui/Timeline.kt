package app.demotape.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.demotape.LiveTake
import app.demotape.audio.Metronome
import app.demotape.audio.PEAK_HOP
import app.demotape.audio.SR
import app.demotape.data.Clip
import app.demotape.data.Marker
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
 * With the beat grid on, a tap lands on a nearby bar or beat (see [gridSnap]).
 * Section markers live in the ruler: hold an empty spot to add one, tap one to jump there,
 * drag one to move it, hold one for its menu.
 * While a take is being lined up ([nudgeClip]), a one-finger drag that starts on that
 * take's track slides the take instead of scrolling.
 */
@Composable
fun Timeline(
    song: Song,
    peaks: Map<String, ByteArray>,
    samples: (String) -> ShortBuffer?,
    position: () -> Long,
    anchor: () -> app.demotape.audio.AudioEngine.Anchor?,
    follow: Boolean,
    live: LiveTake?,
    nudgeClip: String?,
    onSeek: (Long) -> Unit,
    onLongPressClip: (Clip) -> Unit,
    onNudgeDrag: (Long) -> Unit,
    onTrim: (edge: Int, frame: Long) -> Unit,
    onTrimDone: () -> Unit,
    loopOn: Boolean,
    loopStart: Long,
    loopEnd: Long,
    snapFrames: Double?,
    onLoopChange: (Long, Long) -> Unit,
    onLoopDone: () -> Unit,
    onViewport: (Long, Long) -> Unit,
    onAddMarker: (Long) -> Unit,
    onMarkerMenu: (Marker) -> Unit,
    onMarkerMove: (String, Long) -> Unit,
    onMarkerMoveDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var pps by remember { mutableFloatStateOf(with(density) { 56.dp.toPx() }) } // px per second
    // The song's start sits a little in from the left edge (away from Android's back gesture),
    // so takes, trims and markers at bar 1 are easy to grab.
    val leadPx = with(density) { LEAD.toPx() }
    var viewStart by remember { mutableFloatStateOf(-LEAD.value / 56f) } // seconds
    var widthPx by remember { mutableIntStateOf(1) }
    val tm = rememberTextMeasurer()
    val rulerH = with(density) { 30.dp.toPx() }

    val songNow by rememberUpdatedState(song)
    val nudgeNow by rememberUpdatedState(nudgeClip)
    val seekCb by rememberUpdatedState(onSeek)
    val longCb by rememberUpdatedState(onLongPressClip)
    val nudgeCb by rememberUpdatedState(onNudgeDrag)
    val trimCb by rememberUpdatedState(onTrim)
    val trimDoneCb by rememberUpdatedState(onTrimDone)
    val followNow by rememberUpdatedState(follow)
    val posNow by rememberUpdatedState(position)
    val loopOnNow by rememberUpdatedState(loopOn)
    val loopStartNow by rememberUpdatedState(loopStart)
    val loopEndNow by rememberUpdatedState(loopEnd)
    val snapNow by rememberUpdatedState(snapFrames)
    val loopCb by rememberUpdatedState(onLoopChange)
    val loopDoneCb by rememberUpdatedState(onLoopDone)
    val viewportCb by rememberUpdatedState(onViewport)
    val addMarkerCb by rememberUpdatedState(onAddMarker)
    val markerMenuCb by rememberUpdatedState(onMarkerMenu)
    val markerMoveCb by rememberUpdatedState(onMarkerMove)
    val markerDoneCb by rememberUpdatedState(onMarkerMoveDone)
    val haptic = LocalHapticFeedback.current

    // Report what's on screen (used to place a new loop).
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(viewStart, pps, widthPx) }.collect { (vs, p, w) ->
            viewportCb((vs * SR).toLong(), ((vs + w / p) * SR).toLong())
        }
    }

    // While playing: a steady "flywheel" clock advances the playhead every screen frame and is
    // gently steered toward the engine's timing anchors (a song frame plus the exact moment it was
    // heard), so it never stutters. The playhead sits in the middle with the song scrolling under
    // it, at full precision. Dragging or pinching takes over the view; 3 s after letting go it
    // glides back.
    var smoothPos by remember { mutableLongStateOf(-1L) }
    var followStartF by remember { mutableDoubleStateOf(-1.0) }
    var userScrollAt by remember { mutableLongStateOf(0L) }
    val anchorNow by rememberUpdatedState(anchor)
    LaunchedEffect(follow) {
        if (!follow) {
            smoothPos = -1L
            followStartF = -1.0
            return@LaunchedEffect
        }
        var disp = -1.0
        var lastT = 0L
        var lastEpoch = 0
        // Ease into the centered view instead of snapping when play starts.
        var gliding = true
        while (true) {
            withFrameNanos { t ->
                val a = anchorNow()
                // Real timing reports can be stamped a moment in the future: count from them as
                // they are. The start-up estimate (output still waking up) has nothing before it.
                val clock = when {
                    a == null -> posNow().toDouble()
                    a.estimate -> a.frame + (maxOf(t, a.nanos) - a.nanos) * SR / 1e9
                    else -> a.frame + (t - a.nanos) * SR / 1e9
                }
                if (a == null) {
                    // No exact timing yet (the first moments of playback): stay where the sound
                    // actually is instead of running ahead and snapping back.
                    disp = clock
                } else if (disp < 0 || lastT == 0L || a.epoch != lastEpoch) {
                    // (A tempo change moved playback to keep the beat: go straight there.)
                    disp = clock
                } else {
                    // Follow the sound by changing speed, never by jumping: a bit faster when
                    // behind, a bit slower when ahead, never backwards. Only a real jump (seek,
                    // loop) of more than half a second moves it at once.
                    val dt = (t - lastT) / 1e9
                    val errSec = (clock - disp) / SR
                    if (kotlin.math.abs(errSec) > 0.5) {
                        disp = clock
                    } else {
                        val speed = (1.0 + errSec * 4.0).coerceIn(0.5, 1.6)
                        disp += dt * SR * speed
                    }
                }
                lastT = t
                if (a != null) lastEpoch = a.epoch
                smoothPos = disp.toLong()
                val visF = widthPx / pps * SR.toDouble()
                // Zoomed out with the whole song on screen: hold the view still and let the
                // playhead travel across it. Following picks up if it runs off the edge.
                val s = songNow
                val vs = viewStart.toDouble() * SR
                val firstF = minOf(
                    s.visibleClips.minOfOrNull { it.pos } ?: Long.MAX_VALUE,
                    s.markers.firstOrNull()?.frame ?: Long.MAX_VALUE,
                )
                val lastF = maxOf(s.length, s.markers.lastOrNull()?.frame ?: 0L)
                val wholeSongShown = firstF != Long.MAX_VALUE && lastF > 0 &&
                    firstF >= vs && lastF <= vs + visF && disp >= vs && disp <= vs + visF * 0.97
                if (wholeSongShown) {
                    gliding = true
                    followStartF = -1.0
                } else if (android.os.SystemClock.uptimeMillis() - userScrollAt > 3000) {
                    val target = max(-leadPx / pps * SR.toDouble(), disp - visF / 2)
                    val cur = viewStart.toDouble() * SR
                    if (gliding && kotlin.math.abs(target - cur) * pps / SR > 2f) {
                        viewStart = ((cur + (target - cur) * 0.18) / SR).toFloat()
                        followStartF = -1.0
                    } else {
                        gliding = false
                        followStartF = target
                        viewStart = (target / SR).toFloat()
                    }
                } else {
                    gliding = true
                    followStartF = -1.0
                }
            }
        }
    }

    // Lining up starts where you're looking: the view stays put, and if the playhead
    // is off screen it moves to the left side of the view so play starts here.
    LaunchedEffect(nudgeClip) {
        if (nudgeClip == null) return@LaunchedEffect
        val sec = posNow() / SR.toFloat()
        val vis = widthPx / pps
        if (sec < viewStart || sec > viewStart + vis) {
            seekCb(((viewStart + vis * 0.1f) * SR).toLong().coerceAtLeast(0L))
        }
    }

    Canvas(
        modifier
            .onSizeChanged { widthPx = max(1, it.width) }
            // Holding or dragging in the ruler at the screen edge shouldn't trigger "back".
            .systemGestureExclusion { Rect(0f, 0f, it.size.width.toFloat(), rulerH) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = tap@{ o ->
                        userScrollAt = 0L
                        if (o.y < rulerH) {
                            hitMarker(o, songNow, viewStart, pps, size.width.toFloat(), rulerH, tm)?.let {
                                seekCb(it.frame)
                                return@tap
                            }
                        }
                        val raw = (viewStart + o.x / pps) * SR.toDouble()
                        val f = gridSnap(raw, songNow, pps / SR.toDouble(), 24.dp.toPx(), 14.dp.toPx())
                        if (f != raw) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        seekCb(f.toLong().coerceAtLeast(0L))
                    },
                    onLongPress = press@{ o ->
                        if (o.y < rulerH) {
                            val m = hitMarker(o, songNow, viewStart, pps, size.width.toFloat(), rulerH, tm)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (m != null) {
                                markerMenuCb(m)
                            } else {
                                val raw = (viewStart + o.x / pps) * SR.toDouble()
                                addMarkerCb(gridSnap(raw, songNow, pps / SR.toDouble(), 24.dp.toPx(), 14.dp.toPx()).toLong().coerceAtLeast(0L))
                            }
                            return@press
                        }
                        val laneH = (size.height - rulerH) / TRACKS
                        val lane = floor((o.y - rulerH) / laneH).toInt()
                        if (lane in 0 until TRACKS) {
                            val f = ((viewStart + o.x / pps) * SR).toLong()
                            songNow.clips
                                .filter { !it.hidden && it.track == lane && f >= it.pos && f < it.end }
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
                    // While lining up, grabbing near either edge of the take trims it instead.
                    var trimEdge = 0 // 1 = start, 2 = end
                    var edge0 = 0L
                    if (grabTake && target != null) {
                        val xs = ((target.pos / SR.toFloat()) - viewStart) * pps
                        val xe = ((target.end / SR.toFloat()) - viewStart) * pps
                        val grab = 22.dp.toPx()
                        val dx = down.position.x
                        if (abs(dx - xs) < grab && abs(dx - xs) <= abs(dx - xe)) {
                            trimEdge = 1; edge0 = target.pos
                        } else if (abs(dx - xe) < grab) {
                            trimEdge = 2; edge0 = target.end
                        }
                    }
                    val minPps = 2f
                    val maxPps = size.width / 0.04f // 40 ms across the screen

                    // A marker's flag sits in the top of the ruler: grab it to move the marker.
                    val dragMarker = if (down.position.y < rulerH) {
                        hitMarker(down.position, songNow, viewStart, pps, size.width.toFloat(), rulerH, tm)
                    } else null
                    var markerDx = 0.0
                    // Last grid line a drag snapped to, for a light tick each time it lands on a new one.
                    var snappedTo = Double.NaN
                    fun dragSnap(f: Double): Double {
                        val g = gridSnap(f, songNow, pps / SR.toDouble(), 20.dp.toPx(), 12.dp.toPx())
                        if (g != f && g != snappedTo) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        snappedTo = if (g != f) g else Double.NaN
                        return g
                    }

                    // Loop handles live in the ruler: grab an edge to resize, the middle to move.
                    val ls0 = loopStartNow
                    val le0 = loopEndNow
                    var loopMode = 0 // 1 = start edge, 2 = end edge, 3 = whole loop
                    if (dragMarker == null && loopOnNow && le0 > ls0 && down.position.y < rulerH + 8.dp.toPx()) {
                        val xs = ((ls0 / SR.toFloat()) - viewStart) * pps
                        val xe = ((le0 / SR.toFloat()) - viewStart) * pps
                        val grab = 24.dp.toPx()
                        val dx = down.position.x
                        loopMode = when {
                            abs(dx - xs) < grab && abs(dx - xs) <= abs(dx - xe) -> 1
                            abs(dx - xe) < grab -> 2
                            dx > xs && dx < xe -> 3
                            else -> 0
                        }
                    }
                    var loopDx = 0.0
                    fun snap(f: Double): Long {
                        val b = snapNow ?: return f.toLong()
                        return Math.round(Math.round(f / b) * b)
                    }
                    val minLoop = snapNow?.let { Math.round(it) } ?: (SR / 4L)
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
                        if (dragMarker != null && !multi) {
                            markerDx += pan.x / pps * SR.toDouble()
                            val f = dragSnap(dragMarker.frame + markerDx)
                            markerMoveCb(dragMarker.id, f.toLong().coerceAtLeast(0L))
                        } else if (loopMode != 0 && !multi) {
                            loopDx += pan.x / pps * SR.toDouble()
                            when (loopMode) {
                                1 -> {
                                    val a = snap(ls0 + loopDx).coerceAtLeast(0L)
                                    if (le0 - a >= minLoop) loopCb(a, le0)
                                }
                                2 -> {
                                    val b = snap(le0 + loopDx)
                                    if (b - ls0 >= minLoop) loopCb(ls0, b)
                                }
                                else -> {
                                    val a = snap(ls0 + loopDx).coerceAtLeast(0L)
                                    loopCb(a, a + (le0 - ls0))
                                }
                            }
                        } else if (trimEdge != 0 && !multi) {
                            acc += pan.x / pps * SR.toDouble()
                            // With the grid on, edges snap like a tap does: bars from farther away than
                            // beats, by distance on screen, so zoomed in it's easy to stop between.
                            val f = dragSnap(edge0 + acc)
                            trimCb(trimEdge, f.toLong())
                        } else if (grabTake && !multi) {
                            acc += pan.x / pps * SR.toDouble()
                            val whole = acc.toLong()
                            if (whole != 0L) {
                                nudgeCb(whole)
                                acc -= whole
                            }
                        } else {
                            userScrollAt = android.os.SystemClock.uptimeMillis()
                            val c = ev.calculateCentroid(useCurrent = false)
                            val cx = if (c.isSpecified) c.x else 0f
                            val anchor = viewStart + cx / pps
                            pps = (pps * zoom).coerceIn(minPps, max(minPps, maxPps))
                            viewStart = max(-leadPx / pps, anchor - cx / pps - pan.x / pps)
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    if (loopMode != 0 && moving) loopDoneCb()
                    if (dragMarker != null && moving) markerDoneCb()
                    if (trimEdge != 0 && moving) trimDoneCb()
                }
            }
    ) {
        val laneH = (size.height - rulerH) / TRACKS
        // While following, the view start is kept at full precision (Float seconds get coarse
        // late in a song at deep zoom, which shows up as wobble).
        val startF = if (followStartF >= 0) followStartF else viewStart.toDouble() * SR
        val pxPerFrame = pps.toDouble() / SR

        // lanes
        for (t in 0 until TRACKS) {
            drawRect(if (t % 2 == 0) LaneA else LaneB, Offset(0f, rulerH + t * laneH), Size(size.width, laneH))
        }
        // Before the song starts: a little darker.
        val x0 = (-startF * pxPerFrame).toFloat()
        if (x0 > 0f) drawRect(Bg.copy(alpha = 0.55f), Offset(0f, rulerH), Size(min(x0, size.width), size.height - rulerH))
        nudgeClip?.let { id ->
            song.clips.find { it.id == id }?.let { c ->
                drawRect(TrackColors[c.track].copy(alpha = 0.07f), Offset(0f, rulerH + c.track * laneH), Size(size.width, laneH))
            }
        }

        // While lining up, show the whole recording faintly behind the take, so you can see
        // what trimming has hidden and drag an edge back out.
        nudgeClip?.let { id ->
            song.clips.find { it.id == id }?.let { c ->
                val cap = samples(c.fileId)?.capacity()?.toLong()
                if (cap != null && (c.skip > 0 || c.skip + c.length < cap)) {
                    drawTake(
                        pos = c.pos - c.skip, length = cap, skip = 0L, track = c.track,
                        peaks = peaks[c.fileId], samples = samples(c.fileId),
                        color = TrackColors[c.track].copy(alpha = 0.28f), highlight = false,
                        rulerH = rulerH, laneH = laneH, startF = startF, pxPerFrame = pxPerFrame,
                        gain = c.gainLin,
                    )
                }
            }
        }

        // takes, oldest first so newer takes cover what they replace
        for (c in song.clips) {
            if (c.hidden) continue
            val muted = !song.audible(c.track)
            drawTake(
                pos = c.pos, length = c.length, skip = c.skip, track = c.track, peaks = peaks[c.fileId], samples = samples(c.fileId),
                color = TrackColors[c.track].let { if (muted) it.copy(alpha = 0.35f) else it },
                highlight = c.id == nudgeClip, rulerH = rulerH, laneH = laneH,
                startF = startF, pxPerFrame = pxPerFrame,
                gain = c.gainLin, badge = if (!c.video) 0 else if (c.videoHidden) 2 else 1,
            )
        }
        live?.let { lt ->
            // The recording region's edge rides exactly on the playhead; the waveform fills in
            // inside it as the mic delivers sound (a few milliseconds behind).
            val head = if (smoothPos >= 0) smoothPos else position()
            val have = lt.peaks.size.toLong() / 2 * PEAK_HOP
            // Never past the playhead, so the take and the playhead always meet exactly.
            val len = if (head > lt.from) head - lt.from else 0L
            drawTake(
                pos = lt.from, length = len, skip = 0L, track = lt.track, peaks = lt.peaks,
                samples = null, color = RecRed, highlight = false, rulerH = rulerH, laneH = laneH,
                startF = startF, pxPerFrame = pxPerFrame,
            )
        }

        // loop region over the lanes
        val loopVisible = loopOn && loopEnd > loopStart
        val lxs = ((loopStart - startF) * pxPerFrame).toFloat()
        val lxe = ((loopEnd - startF) * pxPerFrame).toFloat()
        if (loopVisible) {
            val a = lxs.coerceAtLeast(0f)
            val b = lxe.coerceAtMost(size.width)
            if (b > a) drawRect(Cream.copy(alpha = 0.05f), Offset(a, rulerH), Size(b - a, size.height - rulerH))
            for (x in floatArrayOf(lxs, lxe)) {
                if (x >= 0 && x <= size.width) drawLine(Cream.copy(alpha = 0.45f), Offset(x, rulerH), Offset(x, size.height), 1.5.dp.toPx())
            }
        }

        // where each section starts, faintly, through the lanes
        for (m in song.markers) {
            val x = ((m.frame - startF) * pxPerFrame).toFloat()
            if (x >= 0 && x <= size.width) {
                drawLine(MarkerColor.copy(alpha = 0.3f), Offset(x, rulerH), Offset(x, size.height), 1.dp.toPx())
            }
        }

        // beat grid while the metronome is on
        if (song.gridOn) {
            val bl = Metronome.beatLength(song.bpm)
            val beatPx = bl * pxPerFrame
            if (beatPx >= 6.0) {
                var k = floor(startF / bl).toLong()
                while (true) {
                    val x = ((k * bl - startF) * pxPerFrame).toFloat()
                    if (x > size.width) break
                    if (x >= 0 && k >= 0) {
                        val barLine = Math.floorMod(k, song.beatsPerBar.toLong()) == 0L
                        drawLine(
                            Cream.copy(alpha = if (barLine) 0.22f else 0.08f),
                            Offset(x, rulerH), Offset(x, size.height),
                            if (barLine) 1.5.dp.toPx() else 1f,
                        )
                    }
                    k++
                }
            }
        }

        // track numbers. Measure first, then draw: drawing straight from the measurer
        // crashes when text starts at or past the canvas edge.
        for (t in 0 until TRACKS) {
            val nm = song.tracks[t].name
            val label = if (nm.isBlank()) "${t + 1}" else "${t + 1}  $nm"
            val layout = tm.measure(label, TextStyle(color = TrackColors[t].copy(alpha = 0.85f), fontSize = 11.sp))
            if (nm.isNotBlank()) {
                drawRoundRect(
                    Bg.copy(alpha = 0.6f),
                    Offset(3.dp.toPx(), rulerH + t * laneH + 3.dp.toPx()),
                    Size(layout.size.width + 6.dp.toPx(), layout.size.height + 2.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                )
            }
            drawText(layout, topLeft = Offset(6.dp.toPx(), rulerH + t * laneH + 4.dp.toPx()))
        }

        // ruler
        drawRect(Bg, Offset.Zero, Size(size.width, rulerH))
        if (song.gridOn) {
            // Metronome or drums on: count bars, and let the beat grid be the only lines.
            val barLen = Metronome.beatLength(song.bpm) * song.beatsPerBar
            val barPx = barLen * pxPerFrame
            val every = listOf(1, 2, 4, 8, 16, 32, 64).firstOrNull { it * barPx >= 34.dp.toPx() } ?: 128
            var b = (floor(startF / barLen).toLong() / every) * every
            while (true) {
                val x = ((b * barLen - startF) * pxPerFrame).toFloat()
                if (x > size.width) break
                if (x >= 0 && b >= 0) {
                    drawLine(Faint, Offset(x, rulerH - 6.dp.toPx()), Offset(x, rulerH), 1.dp.toPx())
                    if (x + 3.dp.toPx() < size.width) {
                        drawText(
                            tm.measure("${b + 1}", TextStyle(color = Muted, fontSize = 10.sp)),
                            topLeft = Offset(x + 3.dp.toPx(), 3.dp.toPx()),
                        )
                    }
                }
                b += every
            }
        } else {
            val steps = floatArrayOf(
                0.005f, 0.01f, 0.025f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f,
            )
            val iv = steps.firstOrNull { it * pps >= 70.dp.toPx() } ?: 600f
            var i = floor(viewStart / iv).toLong()
            val endSec = viewStart + size.width / pps
            while (i * iv <= endSec) {
                val t = i * iv
                val x = (t - viewStart) * pps
                if (x >= 0 && t >= 0) {
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
        }

        // loop band and handles on the ruler
        if (loopVisible) {
            val bandH = 8.dp.toPx()
            val a = lxs.coerceAtLeast(0f)
            val b = lxe.coerceAtMost(size.width)
            if (b > a) drawRect(Cream.copy(alpha = 0.35f), Offset(a, rulerH - bandH), Size(b - a, bandH))
            val hw = 6.dp.toPx()
            val hh = 18.dp.toPx()
            for (x in floatArrayOf(lxs, lxe)) {
                if (x >= -hw && x <= size.width + hw) {
                    drawRoundRect(
                        Cream, Offset(x - hw / 2, rulerH - hh), Size(hw, hh),
                        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }

        // marker flags on top of the ruler
        for (p in markerPills(song.markers, startF, pxPerFrame, size.width, tm)) {
            val top = 3.dp.toPx()
            val h = 16.dp.toPx()
            drawLine(MarkerColor.copy(alpha = 0.7f), Offset(p.x, top), Offset(p.x, rulerH), 1.dp.toPx())
            drawRoundRect(
                MarkerColor, Offset(p.x, top), Size(p.w, h),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            )
            p.label?.let { l ->
                drawText(l, topLeft = Offset(p.x + (p.w - l.size.width) / 2f, top + (h - l.size.height) / 2f))
            }
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
        val ph = if (smoothPos >= 0) smoothPos else position()
        val px = ((ph - startF) * pxPerFrame).toFloat()
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

private val MarkerColor = MarkerAmber

/** Room left of bar 1. */
private val LEAD = 22.dp

/**
 * Snaps a frame to the beat grid (when it's on) if it's close on screen. Bar lines pull from
 * farther than beat lines, and both reach a fixed distance in pixels: zoomed out, where lines are
 * packed together, nearly every tap lands on a bar; zoomed in, there's lots of room between them
 * to land anywhere.
 */
private fun gridSnap(f: Double, s: Song, pxPerFrame: Double, barReach: Float, beatReach: Float): Double {
    if (!s.gridOn) return f
    val bl = Metronome.beatLength(s.bpm)
    val bar = bl * s.beatsPerBar
    val nb = Math.round(f / bar) * bar
    if (abs(nb - f) * pxPerFrame < barReach) return nb
    if (bl * pxPerFrame >= 6.0) { // beat lines are drawn
        val nbt = Math.round(f / bl) * bl
        if (abs(nbt - f) * pxPerFrame < beatReach) return nbt
    }
    return f
}

private class MarkerPill(val m: Marker, val x: Float, val w: Float, val label: TextLayoutResult?)

/**
 * Where each marker's flag goes. A flag never runs into the next marker: when they crowd
 * together (zoomed out) it shows just the first letter, then just a sliver.
 */
private fun Density.markerPills(
    markers: List<Marker>, startF: Double, pxPerFrame: Double, width: Float, tm: TextMeasurer,
): List<MarkerPill> {
    if (markers.isEmpty()) return emptyList()
    val style = TextStyle(color = Bg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    val pad = 5.dp.toPx()
    val xs = markers.map { ((it.frame - startF) * pxPerFrame).toFloat() }
    val out = ArrayList<MarkerPill>()
    for (i in markers.indices) {
        val x = xs[i]
        val room = (xs.getOrNull(i + 1) ?: Float.MAX_VALUE) - x - 2.dp.toPx()
        if (x > width || x + max(room, 0f) < -20.dp.toPx()) continue
        val name = markers[i].name.ifBlank { "•" }
        val full = tm.measure(name, style)
        val fw = full.size.width + pad * 2
        if (fw <= room) {
            out += MarkerPill(markers[i], x, fw, full)
            continue
        }
        val first = tm.measure(name.take(1), style)
        val w1 = first.size.width + pad * 2
        out += if (w1 <= room) MarkerPill(markers[i], x, w1, first)
        else MarkerPill(markers[i], x, room.coerceIn(2.dp.toPx(), 6.dp.toPx()), null)
    }
    return out
}

private fun Density.hitMarker(
    o: Offset, s: Song, viewStartSec: Float, pps: Float, width: Float, rulerH: Float, tm: TextMeasurer,
): Marker? {
    // The bottom of the ruler belongs to the loop handles.
    if (o.y > rulerH - 8.dp.toPx()) return null
    val pills = markerPills(s.markers, viewStartSec * SR.toDouble(), pps / SR.toDouble(), width, tm)
    return pills.lastOrNull { o.x >= it.x - 8.dp.toPx() && o.x <= it.x + max(it.w, 14.dp.toPx()) + 4.dp.toPx() }?.m
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
    skip: Long,
    track: Int,
    peaks: ByteArray?,
    samples: ShortBuffer?,
    color: Color,
    highlight: Boolean,
    rulerH: Float,
    laneH: Float,
    startF: Double,
    pxPerFrame: Double,
    gain: Float = 1f,
    /** 0 = no video, 1 = has video, 2 = has video (hidden). */
    badge: Int = 0,
) {
    val pad = 3.dp.toPx()
    // Linear scale, like Ableton: quiet takes look quiet. The take's volume is included,
    // so boosting a quiet take makes its waveform grow to match.
    val g = gain
    val top = rulerH + track * laneH + pad
    val h = laneH - pad * 2
    val mid = top + h / 2
    val x0 = ((pos - startF) * pxPerFrame).toFloat().coerceAtLeast(0f)
    val x1 = ((pos + length - startF) * pxPerFrame).toFloat().coerceAtMost(size.width)
    if (x1 <= x0) return
    val laneBg = if (track % 2 == 0) LaneA else LaneB
    drawRect(lerp(laneBg, color, 0.16f), Offset(x0, top), Size(x1 - x0, h))
    drawLine(color.copy(alpha = 0.4f), Offset(x0, mid), Offset(x1, mid), 1f)

    val half = h * 0.47f
    fun y(v: Float) = mid - (v * g).coerceIn(-1f, 1f) * half
    val org = pos - skip // song frame of the file's first sample
    val framesPerPx = 1.0 / pxPerFrame
    // Columns sit on a fixed grid in song time (not screen time), so scrolling slides the
    // shape instead of re-sampling it, and it doesn't shimmer.
    val gridOff = startF * pxPerFrame
    val c0 = floor(x0 + gridOff).toLong()
    val c1 = kotlin.math.ceil(x1 + gridOff).toLong()
    val count = (c1 - c0).toInt().coerceAtLeast(0)
    if (count == 0) return
    val tops = FloatArray(count)
    val bots = FloatArray(count)
    var have = 0
    var firstCol = 0L

    if (samples != null && framesPerPx < 1.5) {
        // Very close: draw the actual sample line.
        val cap = min(samples.capacity().toLong(), skip + length)
        val path = Path()
        var started = false
        var f = ((startF + x0 / pxPerFrame) - org).toLong().coerceAtLeast(skip) - 1
        val fEnd = ((startF + x1 / pxPerFrame) - org).toLong() + 1
        while (f <= fEnd) {
            if (f in skip until cap) {
                val x = ((org + f - startF) * pxPerFrame).toFloat()
                val yy = y(samples.get(f.toInt()) / 32767f)
                if (!started) { path.moveTo(x, yy); started = true } else path.lineTo(x, yy)
            }
            f++
        }
        if (started) drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
    } else {
        val useSamples = samples != null && framesPerPx < PEAK_HOP
        val cap = if (samples != null) min(samples.capacity().toLong(), skip + length) else 0L
        val hops = (peaks?.size ?: 0) / 2
        val firstHop = (skip / PEAK_HOP).toInt()
        val lastHop = min(hops.toLong() - 1, (skip + length - 1) / PEAK_HOP).toInt()
        for (ci in 0 until count) {
            val col = c0 + ci
            val fa = (col / pxPerFrame) - org
            val fb = ((col + 1) / pxPerFrame) - org
            var hi = -1f
            var lo = 1f
            var any = false
            if (useSamples) {
                val a = fa.toLong().coerceAtLeast(skip)
                val b = (fb.toLong()).coerceAtMost(cap - 1)
                var f = a
                while (f <= b) {
                    val v = samples!!.get(f.toInt()) / 32767f
                    if (v > hi) hi = v
                    if (v < lo) lo = v
                    any = true
                    f++
                }
            } else if (peaks != null && hops > 0) {
                val ia = (fa / PEAK_HOP).toInt().coerceAtLeast(firstHop)
                val ib = (fb / PEAK_HOP).toInt().coerceAtMost(lastHop)
                var k = ia
                while (k <= ib) {
                    val vh = peaks[2 * k] / 127f
                    val vl = peaks[2 * k + 1] / 127f
                    if (vh > hi) hi = vh
                    if (vl < lo) lo = vl
                    any = true
                    k++
                }
            }
            if (!any) {
                // Past the end of what's recorded so far: close the shape on the center line.
                if (have > 0) break else continue
            }
            tops[have] = y(hi)
            bots[have] = max(y(lo), tops[have] + 1f)
            have++
            if (have == 1) firstCol = col
        }
        if (have > 0) {
            val path = Path()
            val xs = (firstCol - gridOff).toFloat()
            path.moveTo(xs, tops[0])
            for (k in 1 until have) path.lineTo(xs + k, tops[k])
            path.lineTo(xs + have, tops[have - 1])
            path.lineTo(xs + have, bots[have - 1])
            for (k in have - 1 downTo 0) path.lineTo(xs + k, bots[k])
            path.close()
            drawPath(path, color)
        }
    }
    drawRect(
        if (highlight) Cream else color.copy(alpha = 0.55f), Offset(x0, top), Size(x1 - x0, h),
        style = Stroke(width = if (highlight) 2.dp.toPx() else 1.dp.toPx()),
    )
    if (highlight) {
        // Trim handles at both edges of the take being lined up.
        val hw = 5.dp.toPx()
        val hh = h * 0.5f
        val sx = ((pos - startF) * pxPerFrame).toFloat()
        val ex = ((pos + length - startF) * pxPerFrame).toFloat()
        for (x in floatArrayOf(sx, ex)) {
            if (x >= -hw && x <= size.width + hw) {
                drawRoundRect(
                    Cream, Offset(x - hw / 2, mid - hh / 2), Size(hw, hh),
                    androidx.compose.ui.geometry.CornerRadius(hw / 2),
                )
            }
        }
    }
    if (badge != 0 && x1 - x0 > 30.dp.toPx()) drawVideoBadge(x1 - 23.dp.toPx(), top + 3.dp.toPx(), color, badge == 2)
}

/** Small camera icon in a take's corner: this take has video (dimmed and struck through if hidden). */
private fun DrawScope.drawVideoBadge(x: Float, y: Float, color: Color, hidden: Boolean) {
    val w = 20.dp.toPx()
    val h = 13.dp.toPx()
    val cr = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
    drawRoundRect(Bg.copy(alpha = 0.75f), Offset(x, y), Size(w, h), cr)
    val c = if (hidden) Muted.copy(alpha = 0.7f) else color
    val bx = x + 3.dp.toPx()
    val by = y + 3.dp.toPx()
    val bw = 9.dp.toPx()
    val bh = 7.dp.toPx()
    drawRoundRect(c, Offset(bx, by), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
    val lens = Path().apply {
        moveTo(bx + bw + 1.dp.toPx(), by + bh / 2)
        lineTo(bx + bw + 5.dp.toPx(), by)
        lineTo(bx + bw + 5.dp.toPx(), by + bh)
        close()
    }
    drawPath(lens, c)
    if (hidden) {
        drawLine(Cream.copy(alpha = 0.8f), Offset(x + 3.dp.toPx(), y + h - 2.dp.toPx()), Offset(x + w - 3.dp.toPx(), y + 2.dp.toPx()), 1.5.dp.toPx())
    }
}

