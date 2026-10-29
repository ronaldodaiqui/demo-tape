package app.demotape.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One topic: a few tips, each a short bold lead-in and a sentence or two. */
private class HelpTopic(
    val title: String,
    val color: Color,
    val icon: ImageVector? = null,
    val glyph: String? = null,
    val tips: List<Pair<String, String>>,
)

private val topics = listOf(
    HelpTopic(
        "Recording", RecRed, icon = Icons.Filled.Mic, tips = listOf(
            "Pick a track." to "Tap a track number (1–4) so it turns red. A new song starts with track 1 ready.",
            "Record." to "Press the red button to start, and again to stop. With the metronome's count-in on, you get one bar of clicks first.",
            "Punch in." to "Press record while the song is playing and recording starts right there. When you stop, playback keeps going if other tracks have sound at that point, so you can keep listening.",
            "Record over a part." to "A new take on a track covers the older one wherever they overlap. Nothing is thrown away: undo brings the old part back.",
            "The mic." to "Recording always uses your phone's built-in mic, even with Bluetooth headphones on.",
            "Clear a track." to "Hold its track number, then tap Clear track.",
        )
    ),
    HelpTopic(
        "Headphones & timing", TrackColors[1], icon = Icons.Filled.Headphones, tips = listOf(
            "Why sync?" to "Bluetooth headphones play everything a little late, so a part you play along to would land late too. Demo-tape corrects for that.",
            "The sync check." to "The first time you overdub with new Bluetooth headphones, you're asked to do a 5-second check: knock on the phone in time with the clicks. After that, every take with those headphones lines up by itself.",
            "Check or redo it." to "The headphones chip on the song screen shows if they're synced. Tap it (or ⋮ → Headphones & sync) to sync again.",
            "Wired or no headphones." to "Nothing to do. The timing is handled automatically.",
            "Still a hair off?" to "Hold the take and use Line up & trim (see Takes).",
        )
    ),
    HelpTopic(
        "Takes", TrackColors[0], icon = Icons.Filled.GraphicEq, tips = listOf(
            "Take menu." to "Hold a take for its menu: take volume, Boost to full, Line up & trim, show or hide its video, and delete.",
            "Line up & trim." to "Slide the take sideways with your finger, or nudge it with the earlier and later buttons. Drag either end to trim it. It works while playing, so you can line it up by ear. Tap Done when it sounds right.",
            "Too quiet?" to "Take volume goes up to +24 dB, and Boost to full raises it as far as it can go without clipping.",
            "After your first overdub" to "a small bar offers to line it up, in case your headphones need a touch of adjustment.",
        )
    ),
    HelpTopic(
        "Playing & moving around", Cream, icon = Icons.Filled.PlayArrow, tips = listOf(
            "Move the playhead." to "Tap anywhere on the timeline. With the metronome or drums on, the tap snaps to a nearby bar or beat.",
            "Scroll and zoom." to "Drag to scroll, pinch to zoom. While playing, the view follows the playhead. Scroll away and it glides back after a few seconds.",
            "Back to the start." to "Press the ⏮ button.",
            "Play anytime." to "Play works even before anything is recorded, so you can practice with the click or drums.",
        )
    ),
    HelpTopic(
        "Looping", TrackColors[2], icon = Icons.Filled.Repeat, tips = listOf(
            "Turn on a loop." to "Tap the loop button. The first time, it loops what's on screen (to whole bars when the metronome or drums are on).",
            "Adjust it." to "Drag the loop's edges in the ruler at the top, or drag the middle to move the whole loop.",
            "Loop recording." to "Record with the loop on and recording goes round and round. Each pass is kept as its own take. Pick the keeper with the arrows on the bar that appears, or later from the take's menu.",
            "Loop a section." to "Hold a marker and tap Loop this section (see Markers).",
        )
    ),
    HelpTopic(
        "Metronome & drums", MuteAmber, glyph = "♩", tips = listOf(
            "Metronome." to "Tap ♩ to set the tempo (slider, − and +, or tap tempo), beats per bar, count-in and click volume. Hold ♩ to turn the click on or off. The click is only in your headphones: it's never recorded or exported.",
            "Drums." to "Tap Drums to open the drum machine. Hold Drums to turn them on or off. Unlike the click, the drums are part of your mix and exports.",
            "Make a beat." to "Start from a preset or tap squares in the grid (kick on the bottom row). Patterns can be 1 to 4 bars: pick which bar you're editing above the grid. Level and Swing are at the bottom, and tempo is at the top.",
            "Save a beat." to "Tap + Save to keep a pattern for any song. Your saved patterns show with a ★. Hold one to delete it.",
            "Change tempo while playing." to "The groove carries on smoothly at the new tempo.",
            "The grid." to "With the click or drums on, the timeline shows bars and beats. Taps, trims and markers snap to nearby lines: firmly when zoomed out, gently when zoomed in.",
        )
    ),
    HelpTopic(
        "Markers", MarkerAmber, glyph = "◆", tips = listOf(
            "Add one." to "Hold an empty spot in the ruler at the top of the timeline, then pick a name like Verse or Chorus (or type your own).",
            "Use them." to "Tap a marker to jump there. Drag it to move it.",
            "Marker menu." to "Hold a marker to rename it, loop from it to the next marker, or delete it.",
        )
    ),
    HelpTopic(
        "Mixing", TrackColors[3], icon = Icons.Filled.Tune, tips = listOf(
            "Faders." to "Drag a track's fader to set its volume. Double-tap to reset it.",
            "M and S." to "M mutes a track. S solos it (you hear only soloed tracks).",
            "Tone & pan." to "The slider button under each track opens Low, Mid (with a sweepable frequency), High, Trim and Pan. Double-tap a knob to reset it. The button lights up when anything is changed.",
            "Bounce." to "⋮ → Bounce… mixes everything you hear (and the drums, if you like) onto one track, to free up the others. The tracks that went in are cleared, and undo brings them back.",
        )
    ),
    HelpTopic(
        "Undo", Cream, icon = Icons.AutoMirrored.Filled.Undo, tips = listOf(
            "Undo anything." to "Tap ↶ to undo the last change: a take, a delete, a tempo change, a mix move. The notice tells you what was undone.",
            "Redo." to "Hold ↶.",
            "How long?" to "Undo goes back as far as you like while the song is open.",
        )
    ),
    HelpTopic(
        "Tuner, notes & video", TrackColors[1], icon = TuningFork, tips = listOf(
            "Tuner." to "Tap the tuning fork. You can change the reference pitch (A4) if you tune to something other than 440.",
            "Notes." to "Tap the notes icon for lyrics, chords and reminders. Play and record are right there, so you can read along while you play. You can also name the tracks there.",
            "Video." to "Tap the camera chip to film while you record. The video stays with that take and plays back in a small window. Hide it from the take's menu if you don't need it.",
        )
    ),
    HelpTopic(
        "Songs & saving", TrackColors[0], icon = Icons.Filled.LibraryMusic, tips = listOf(
            "Saving." to "Everything saves as you go, so nothing is lost if your phone dies.",
            "Old songs." to "If you change a song you opened from the list, leaving asks whether to save, save as a new song, or keep it as it was.",
            "Versions." to "⋮ → Save as new song makes a copy to try ideas on.",
            "The song list." to "Tap a song to open it. Hold it to rename or delete. Search is at the top, and ⋮ lets you sort by name or date.",
        )
    ),
    HelpTopic(
        "Sharing", Good, icon = Icons.Filled.IosShare, tips = listOf(
            "Export." to "⋮ → Export… gives you a Mixdown (the song as you hear it), Stems (each track as its own WAV in one zip, plus the drums if they're on, and a notes file), or a Song backup.",
            "Share or save." to "Send it straight to another app, or save it to your Downloads folder.",
            "Backups." to "A song backup holds everything, video included. Open it on any phone with ⋮ → Import song on the song list.",
        )
    ),
    HelpTopic(
        "Quick capture", RecRed, icon = Icons.Filled.Widgets, tips = listOf(
            "New idea widget." to "Add it to your home screen: one tap opens a new song and starts recording right away.",
            "New song widget." to "Opens a fresh song with track 1 ready, without recording yet.",
            "App shortcuts." to "Hold the Demo-tape icon for the same two shortcuts.",
        )
    ),
)

/** Instructions for everything, grouped by topic. Tap a topic to open it. */
@Composable
fun HelpScreen(onBack: () -> Unit, onIntro: () -> Unit) {
    val open = remember { mutableStateMapOf(0 to true) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Cream)
            }
            Text("Help", color = Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Demo-tape is a simple four-track for song ideas. Tap a topic to see how things work.",
                    color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                )
            }
            items(topics.size) { i ->
                TopicCard(topics[i], expanded = open[i] == true) { open[i] = open[i] != true }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "See the intro again",
                    color = Cream, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onIntro)
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                )
                Text(
                    "Everything stays on your phone. Demo-tape has no internet access, no accounts and no tracking.",
                    color = Faint, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TopicCard(t: HelpTopic, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(t.color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                if (t.icon != null) Icon(t.icon, contentDescription = null, tint = t.color, modifier = Modifier.size(20.dp))
                else Text(t.glyph ?: "", color = t.color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Text(t.title, color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Close" else "Open", tint = Muted,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                t.tips.forEach { (lead, body) ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Cream, fontWeight = FontWeight.SemiBold)) { append(lead) }
                            append(" ")
                            append(body)
                        },
                        color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}
