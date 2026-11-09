package app.demotape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import app.demotape.audio.SR
import app.demotape.data.Song
import app.demotape.data.TRACKS

/** Notes icon with a small dot when the song has notes. */
@Composable
fun BadgedNotesIcon(hasNotes: Boolean) {
    Box {
        Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Notes", tint = Cream)
        if (hasNotes) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MuteAmber)
            )
        }
    }
}

/**
 * A song's notes: track names up top, then free text (lyrics, chords, tuning, reminders) in a
 * monospace font so chords stay lined up over words. A mini transport at the bottom lets you
 * read lyrics while recording.
 */
@Composable
fun NotesScreen(
    song: Song,
    position: () -> Long,
    playing: Boolean,
    recording: Boolean,
    armed: Int,
    onNotes: (String) -> Unit,
    onTrackName: (Int, String) -> Unit,
    onPlay: () -> Unit,
    onRecord: () -> Unit,
    onClose: () -> Unit,
) {
    // Local copy so typing stays snappy; every change is also saved to the song.
    var value by remember(song.id) { mutableStateOf(TextFieldValue(song.notes, TextRange(song.notes.length))) }
    val text = value.text
    // Line ideas: the suggested line (null = strip closed, "" = thinking).
    var idea by remember(song.id) { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("ui", 0) }
    // Rhyme every other line within a verse (remembered).
    var rhyme by remember { mutableStateOf(prefs.getBoolean("ideaRhyme", true)) }
    // Get the line ideas ready in the background, so the first roll is quick.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { runCatching { LyricLines.model(ctx) } }
    }
    // Tab: the fret pad is open while the cursor's in a staff.
    var padOpen by remember(song.id) { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var cols by remember { mutableStateOf(34) }
    var focused by remember { mutableStateOf(false) }
    var lastDigit by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val blocks = remember(text) { Tab.blocks(text) }
    val cursor = value.selection.start
    val inTab = padOpen && blocks.any { cursor in it }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(inTab) {
        if (inTab) keyboard?.hide() else if (focused) keyboard?.show()
    }
    fun press(k: Tab.Key) {
        val now = android.os.SystemClock.uptimeMillis()
        val key = if (k is Tab.Key.Digit) {
            k.copy(combine = lastDigit?.let { it.first == value.selection.start && now - it.second < 1200 } == true)
        } else k
        val (t, c) = Tab.key(value.text, value.selection.start, key, cols) ?: return
        value = TextFieldValue(t, TextRange(c))
        if (t != text) onNotes(t)
        lastDigit = if (k is Tab.Key.Digit) c to now else null
    }
    fun suggest(v: TextFieldValue) {
        idea = ""
        scope.launch {
            idea = withContext(Dispatchers.Default) {
                try {
                    val m = LyricLines.model(ctx)
                    val (word, len) = LyricLines.context(m, LyricLines.linesBefore(v.text, v.selection.end))
                    m.line(if (rhyme) word else null, len, spread = 3)
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Cream)
            }
            Column(Modifier.weight(1f)) {
                Text("Notes", color = Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(song.name, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // A blank tab staff, put in at the cursor.
            IconButton(onClick = {
                val (t, c) = Tab.insertTemplate(value.text, value.selection.end, cols)
                value = TextFieldValue(t, TextRange(c))
                onNotes(t)
                padOpen = true
            }) {
                Text("TAB", color = Cream, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { if (idea == null) suggest(value) else idea = null }) {
                Icon(
                    Icons.Filled.Casino, contentDescription = "Line ideas",
                    tint = if (idea != null) MarkerAmber else Cream,
                )
            }
        }

        // Track names
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until TRACKS / 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in 0..1) {
                        val t = row * 2 + c
                        TrackNameField(t, song.tracks[t].name, { onTrackName(t, it) }, Modifier.weight(1f))
                    }
                }
            }
        }

        // Notes
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface1)
                .padding(14.dp)
        ) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // How many characters fit across, so a staff fits the screen.
                val widthPx = constraints.maxWidth
                LaunchedEffect(widthPx) {
                    val ten = measurer.measure(AnnotatedString("0000000000"), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp)).size.width
                    if (ten > 0) cols = (widthPx * 10 / ten).coerceAtLeast(16)
                }
                val shrink = remember(cols) { tabFit(cols) }
                val boxColor = Surface2
                val cellColor = MarkerAmber.copy(alpha = 0.4f)
                BasicTextField(
                    value = value,
                    onValueChange = {
                        val changed = it.text != value.text
                        val moved = it.selection != value.selection
                        value = it
                        if (changed) onNotes(it.text)
                        if (changed || moved) padOpen = Tab.blocks(it.text).any { b -> it.selection.start in b }
                    },
                    // In a staff the fret pad does the typing, so the keyboard stays away.
                    readOnly = inTab,
                    textStyle = TextStyle(color = Cream, fontSize = 15.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
                    cursorBrush = SolidColor(Cream),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    visualTransformation = shrink,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { focused = it.isFocused }
                        .verticalScroll(rememberScrollState())
                        .drawBehind {
                            val lr = layout ?: return@drawBehind
                            val len = lr.layoutInput.text.length
                            val pad = with(density) { 6.dp.toPx() }
                            for (blk in blocks) {
                                if (blk.end > len) continue
                                val top = lr.getLineTop(lr.getLineForOffset(blk.start))
                                val bottom = lr.getLineBottom(lr.getLineForOffset(blk.end))
                                drawRoundRect(
                                    boxColor,
                                    topLeft = Offset(-pad, top + pad / 3),
                                    size = Size(size.width + pad * 2, bottom - top),
                                    cornerRadius = CornerRadius(pad * 1.5f),
                                )
                            }
                            if (inTab && len > 0) {
                                // The whole note under the cursor (both digits of a 12).
                                val t = lr.layoutInput.text.text
                                var a = cursor.coerceIn(0, len - 1)
                                var z = a
                                if (t[a].isDigit()) {
                                    while (a > 0 && t[a - 1].isDigit()) a--
                                    while (z + 1 < len && t[z + 1].isDigit()) z++
                                }
                                val ra = lr.getBoundingBox(a)
                                val rz = lr.getBoundingBox(z)
                                drawRoundRect(
                                    cellColor, topLeft = ra.topLeft, size = Size(rz.right - ra.left, ra.height),
                                    cornerRadius = CornerRadius(pad / 2),
                                )
                            }
                        },
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                "Lyrics, chords, tuning, capo…\n\nG        C        G\nWords go under the chords",
                                color = Faint, fontSize = 15.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp,
                            )
                        }
                        inner()
                    },
                )
            }
        }

        // Fret pad: edits the staff the cursor's in without the keyboard.
        if (inTab) {
            TabPad(
                onKey = { press(it) },
                onDone = {
                    val b = blocks.firstOrNull { cursor in it }
                    padOpen = false
                    if (b != null && b.end < text.length) value = value.copy(selection = TextRange(b.end + 1))
                },
            )
        }

        // A line idea: add it to the notes, roll another, or close.
        if (!inTab) idea?.let { line ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface2)
                    .padding(start = 14.dp, top = 10.dp),
            ) {
                Text(
                    if (line.isEmpty()) "…" else line,
                    color = Cream, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Italic, lineHeight = 20.sp,
                    modifier = Modifier.padding(end = 14.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Rhyme on/off: on, every other line slant-rhymes within a verse.
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (rhyme) MarkerAmber.copy(alpha = 0.22f) else Surface1)
                            .clickable {
                                rhyme = !rhyme
                                prefs.edit().putBoolean("ideaRhyme", rhyme).apply()
                                suggest(value)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (rhyme) "Rhyme on" else "Rhyme off",
                            color = if (rhyme) MarkerAmber else Muted, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { idea = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Muted)
                    }
                    IconButton(onClick = { if (line.isNotEmpty()) suggest(value) }) {
                        Icon(Icons.Filled.Casino, contentDescription = "Another", tint = MarkerAmber)
                    }
                    IconButton(onClick = {
                        if (line.isNotEmpty()) {
                            val (t, c) = LyricLines.insert(value.text, value.selection.end, line)
                            val nv = TextFieldValue(t, TextRange(c))
                            value = nv
                            onNotes(t)
                            suggest(nv)
                        }
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "Add to notes", tint = Cream)
                    }
                }
            }
        }

        // Mini transport: read the lyrics while you record.
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatTime(position(), SR),
                    color = if (recording) RecRed else Cream,
                    fontFamily = FontFamily.Monospace, fontSize = 22.sp,
                )
                if (armed >= 0) {
                    Text(
                        "Track ${armed + 1}" + song.tracks[armed].name.let { if (it.isBlank()) "" else " · $it" },
                        color = TrackColors[armed], fontSize = 12.sp,
                    )
                }
            }
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(Surface2).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when {
                    recording -> Icons.Filled.Stop
                    playing -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                }
                Icon(icon, contentDescription = "Play", tint = Cream, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.size(60.dp).clip(CircleShape).border(3.dp, Cream, CircleShape).clickable(onClick = onRecord),
                contentAlignment = Alignment.Center,
            ) {
                if (recording) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(RecRed))
                } else {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(RecRed))
                }
            }
        }
    }
}

@Composable
private fun TrackNameField(track: Int, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    var text by remember(track) { mutableStateOf(value) }
    val color = TrackColors[track]
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("${track + 1}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = text,
            onValueChange = {
                text = it.take(16).replace("\n", "")
                onChange(text)
            },
            singleLine = true,
            textStyle = TextStyle(color = Cream, fontSize = 15.sp),
            cursorBrush = SolidColor(Cream),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("Name", color = Faint, fontSize = 15.sp)
                inner()
            },
        )
    }
}

/** Staves too wide for the screen are drawn smaller so each string stays on one line. */
private fun tabFit(cols: Int) = VisualTransformation { t ->
    val out = AnnotatedString.Builder(t)
    for (b in Tab.blocks(t.text)) {
        val widest = (0 until b.strings).maxOf { b.lineEnds[it] - b.lineStarts[it] }
        if (widest > cols) out.addStyle(SpanStyle(fontSize = (15f * cols / widest).coerceAtLeast(8f).sp), b.start, b.end)
    }
    TransformedText(out.toAnnotatedString(), OffsetMapping.Identity)
}

/** The fret pad: numbers, technique marks, moving around, and beats. */
@Composable
private fun TabPad(onKey: (Tab.Key) -> Unit, onDone: () -> Unit) {
    @Composable
    fun PadKey(label: String, mod: Modifier, accent: Boolean = false, onClick: () -> Unit) {
        Box(
            mod
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (accent) MarkerAmber.copy(alpha = 0.22f) else Surface1)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, color = if (accent) MarkerAmber else Cream, fontSize = if (label.length > 2) 12.sp else 16.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1,
            )
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (d in 0..9) PadKey("$d", Modifier.weight(1f)) { onKey(Tab.Key.Digit(d)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (c in listOf('h', 'p', '/', '\\', 'b', '~', 'x')) PadKey("$c", Modifier.weight(1f)) { onKey(Tab.Key.Mark(c)) }
            PadKey("⌫", Modifier.weight(1.4f)) { onKey(Tab.Key.Clear) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PadKey("←", Modifier.weight(1f)) { onKey(Tab.Key.Left) }
            PadKey("↑", Modifier.weight(1f)) { onKey(Tab.Key.Up) }
            PadKey("↓", Modifier.weight(1f)) { onKey(Tab.Key.Down) }
            PadKey("→", Modifier.weight(1f)) { onKey(Tab.Key.Right) }
            PadKey("+beat", Modifier.weight(1.5f)) { onKey(Tab.Key.AddBeat) }
            PadKey("−beat", Modifier.weight(1.5f)) { onKey(Tab.Key.RemoveBeat) }
            PadKey("+tab", Modifier.weight(1.4f)) { onKey(Tab.Key.NewStaff) }
            PadKey("Done", Modifier.weight(1.5f), accent = true, onClick = onDone)
        }
    }
}
