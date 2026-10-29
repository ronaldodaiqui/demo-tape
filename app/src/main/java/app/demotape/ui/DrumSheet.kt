@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.demotape.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.demotape.audio.Drums
import app.demotape.data.Song
import kotlin.math.floor
import kotlin.math.roundToInt

private val VoiceColors = listOf(
    TrackColors[0], TrackColors[1], TrackColors[2], Color(0xFFF0D98A), TrackColors[3], Cream,
)
private val RowH = 36.dp

/** Step-sequencer drums: on/off, tempo, starting patterns, one to four bars, a tap grid, level and swing. */
@Composable
fun DrumSheet(
    song: Song,
    position: () -> Long,
    onToggle: () -> Unit,
    onChange: ((Song) -> Song) -> Unit,
    onPreset: (String) -> Unit,
    playing: Boolean,
    onPlay: () -> Unit,
    saved: List<app.demotape.data.SavedPattern>,
    onSave: (String) -> Unit,
    onUseSaved: (app.demotape.data.SavedPattern) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var bar by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val shownBar = bar.coerceAtMost(song.drumBars - 1)
    val spb = Drums.stepsPerBar(song)
    AppSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Drums", color = Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            PanelPlayButton(playing, onPlay)
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = song.drumsOn,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Bg, checkedTrackColor = Cream,
                    uncheckedThumbColor = Muted, uncheckedTrackColor = Surface2, uncheckedBorderColor = Faint,
                ),
            )
        }
        Text(
            "Plays along with the metronome's tempo and is part of the mix and exports.",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))

        // Tempo, same as in the metronome panel (it's the song's tempo).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tempo", color = Cream, fontSize = 15.sp, modifier = Modifier.width(64.dp))
            Slider(
                value = song.bpm.toFloat().coerceIn(40f, 240f),
                onValueChange = { v -> onChange { it.copy(bpm = v.roundToInt()) } },
                valueRange = 40f..240f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Cream, activeTrackColor = Cream, inactiveTrackColor = Surface2),
            )
            Spacer(Modifier.width(8.dp))
            StepButton("−") { onChange { it.copy(bpm = (it.bpm - 1).coerceAtLeast(30)) } }
            Text(
                "${song.bpm}", color = Cream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.width(42.dp),
            )
            StepButton("+") { onChange { it.copy(bpm = (it.bpm + 1).coerceAtMost(300)) } }
        }
        Spacer(Modifier.height(10.dp))

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Drums.presets.forEach { (name, _) ->
                Pill(name, selected = false) { onPreset(name) }
            }
            // Your saved patterns, from any song. Hold one to delete it.
            saved.forEach { p ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Bg)
                        .combinedClickable(onClick = { onUseSaved(p) }, onLongClick = { deleting = p.name })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("★ ${p.name}", color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (song.hasDrumPattern) {
                Pill("+ Save", selected = false, quiet = true) { naming = true }
            }
        }
        if (naming) {
            RenameDialog(
                "",
                onDone = { n -> naming = false; if (n.isNotBlank()) onSave(n) },
                onDismiss = { naming = false },
            )
        }
        deleting?.let { n ->
            ConfirmDialog(
                "Delete “$n”?", "It's removed from your saved patterns. Songs using it keep their beat.", "Delete",
                onConfirm = { deleting = null; onDeleteSaved(n) }, onDismiss = { deleting = null },
            )
        }
        Spacer(Modifier.height(14.dp))

        // Pattern length, and (for longer patterns) which bar you're editing.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bars", color = Cream, fontSize = 15.sp, modifier = Modifier.width(58.dp))
            for (n in 1..Drums.MAX_BARS) {
                Pill("$n", selected = song.drumBars == n) {
                    onChange { s -> if (s.drumBars == n) s else extendBars(s, n).copy(drumBars = n) }
                    if (bar >= n) bar = n - 1
                }
            }
        }
        if (song.drumBars > 1) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Editing", color = Muted, fontSize = 13.sp, modifier = Modifier.width(58.dp))
                for (b in 0 until song.drumBars) {
                    Pill("Bar ${b + 1}", selected = shownBar == b, quiet = true) { bar = b }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Kick at the bottom, cymbals and toms on top, like most drum machines.
        Row {
            Column(Modifier.width(52.dp)) {
                for (r in 0 until Drums.VOICES) {
                    val v = voiceAt(r)
                    Box(Modifier.height(RowH), contentAlignment = Alignment.CenterStart) {
                        Text(Drums.NAMES[v], color = VoiceColors[v], fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            StepGrid(
                song = song, offset = shownBar * spb, steps = spb, position = position,
                onToggle = { v, step ->
                    onChange { s -> s.copy(drumRows = toggled(s.drumRows, v, step)) }
                },
                modifier = Modifier.weight(1f).height(RowH * Drums.VOICES),
            )
        }
        Text(
            "Tap a square to add or remove a hit.",
            color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Level", color = Cream, fontSize = 15.sp, modifier = Modifier.width(64.dp))
            Slider(
                value = song.drumVolume, onValueChange = { v -> onChange { it.copy(drumVolume = v) } },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Cream, activeTrackColor = Cream, inactiveTrackColor = Surface2),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Swing", color = Cream, fontSize = 15.sp, modifier = Modifier.width(64.dp))
            Slider(
                value = song.drumSwing, onValueChange = { v -> onChange { it.copy(drumSwing = v) } },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Cream, activeTrackColor = Cream, inactiveTrackColor = Surface2),
            )
        }
    }
}

@Composable
private fun StepGrid(
    song: Song,
    offset: Int,
    steps: Int,
    position: () -> Long,
    onToggle: (voice: Int, step: Int) -> Unit,
    modifier: Modifier,
) {
    val songNow by rememberUpdatedState(song)
    val offsetNow by rememberUpdatedState(offset)
    val stepsNow by rememberUpdatedState(steps)
    val cb by rememberUpdatedState(onToggle)
    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { o ->
                val cw = size.width / stepsNow.toFloat()
                val rh = size.height / Drums.VOICES.toFloat()
                val col = (o.x / cw).toInt()
                val row = (o.y / rh).toInt()
                if (col in 0 until stepsNow && row in 0 until Drums.VOICES) cb(voiceAt(row), offsetNow + col)
            }
        }
    ) {
        val s = songNow
        val cw = size.width / steps
        val rh = size.height / Drums.VOICES
        val gap = 2.5.dp.toPx()
        // Step currently under the playhead.
        val total = Drums.steps(s)
        val k = floor(position() / Drums.stepLength(s)).toLong()
        val cur = Math.floorMod(k, total.toLong()).toInt() - offset
        for (r in 0 until Drums.VOICES) {
            val v = voiceAt(r)
            val row = s.drumRows.getOrNull(v) ?: ""
            for (c in 0 until steps) {
                val idx = offset + c
                val on = idx < row.length && row[idx] == 'x'
                val beatStart = c % 4 == 0
                val color = when {
                    on -> VoiceColors[v]
                    beatStart -> lerp(Surface2, Cream, 0.10f)
                    else -> Surface2
                }
                drawRoundRect(
                    color,
                    Offset(c * cw + gap / 2, r * rh + gap / 2),
                    Size(cw - gap, rh - gap),
                    CornerRadius(5.dp.toPx()),
                )
            }
        }
        if (cur in 0 until steps) {
            drawRoundRect(
                Cream,
                Offset(cur * cw + 1f, 1f),
                Size(cw - 2f, size.height - 2f),
                CornerRadius(5.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Cream else if (quiet) Color.Transparent else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Bg else Cream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Grid row (top to bottom) to drum voice: the kick sits on the bottom row. */
private fun voiceAt(row: Int) = Drums.VOICES - 1 - row

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Cream, fontSize = 20.sp)
    }
}

private fun padRow(r: String) = r.padEnd(Drums.MAX_STEPS, '.').take(Drums.MAX_STEPS)

private fun toggled(rows: List<String>, voice: Int, step: Int): List<String> {
    val all = List(Drums.VOICES) { padRow(rows.getOrNull(it) ?: "") }
    return all.mapIndexed { v, r ->
        if (v != voice || step !in r.indices) r
        else r.substring(0, step) + (if (r[step] == 'x') '.' else 'x') + r.substring(step + 1)
    }
}

/**
 * Making the pattern longer: each new bar starts as a copy of the existing ones, repeating
 * (1 → 4 copies bar 1; 2 → 4 copies bars 1 and 2). A bar that still has hits from before
 * (you shortened, then lengthened again) is kept as it was.
 */
private fun extendBars(s: Song, n: Int): Song {
    val old = s.drumBars
    if (n <= old) return s
    val spb = Drums.stepsPerBar(s)
    val rows = List(Drums.VOICES) { padRow(s.drumRows.getOrNull(it) ?: "") }.map { r ->
        val sb = StringBuilder(r)
        for (b in old until n) {
            val start = b * spb
            if (start + spb > sb.length) break
            if (r.substring(start, start + spb).contains('x')) continue
            val src = (b % old) * spb
            for (i in 0 until spb) sb.setCharAt(start + i, r[src + i])
        }
        sb.toString()
    }
    return s.copy(drumRows = rows)
}
