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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Address for "Send feedback" in About. Empty hides the button. */
const val FEEDBACK_EMAIL = ""

private class IntroPage(val title: String, val body: String, val art: @Composable () -> Unit)

/** Three quick pages shown on first launch (and from Help). */
@Composable
fun IntroDialog(onDone: () -> Unit) {
    val pages = listOf(
        IntroPage(
            "Catch ideas fast",
            "Tap a track number to arm it, then hit record. Add the New idea widget to your home screen to start recording in one tap.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                for (t in 0..3) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(if (t == 0) RecRed else Bg)
                            .border(2.dp, if (t == 0) RecRed else TrackColors[t], CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${t + 1}", color = if (t == 0) Cream else TrackColors[t], fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(56.dp).clip(CircleShape).border(3.dp, Cream, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(RecRed))
                }
            }
        },
        IntroPage(
            "Overdubs that line up",
            "Listen on headphones while you play. With Bluetooth, a 5-second sync check (knock along to the clicks) makes every take land on time. Recording always uses your phone's mic.",
        ) {
            Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Cream, modifier = Modifier.size(64.dp))
        },
        IntroPage(
            "Tidy up, then share",
            "Hold a take to line it up, trim it or change its volume. ↶ undoes anything (hold it to redo). Export a mixdown, stems, or a full backup from the ⋮ menu.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = TrackColors[1], modifier = Modifier.size(56.dp))
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = Cream, modifier = Modifier.size(56.dp))
            }
        },
    )
    var i by remember { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "Skip", color = Muted, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDone).padding(10.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.height(120.dp), contentAlignment = Alignment.Center) { pages[i].art() }
            Spacer(Modifier.height(32.dp))
            Text(pages[i].title, color = Cream, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(pages[i].body, color = Muted, fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (it == i) Cream else Faint))
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Cream)
                    .clickable { if (i < pages.lastIndex) i++ else onDone() },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (i < pages.lastIndex) "Next" else "Start recording", color = Bg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Version, what the app does with your data (nothing leaves the phone), and feedback. */
@Composable
fun AboutSheet(onHelp: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    AppSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TrackColors.forEach { Box(Modifier.size(width = 6.dp, height = 22.dp).clip(RoundedCornerShape(3.dp)).background(it)) }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Demo-tape", color = Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Version $version", color = Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "A simple four-track for song ideas.\n\nEverything stays on your phone. Demo-tape has no internet access, no accounts and no tracking. The microphone is used only while you record or tune, and the camera only when you turn on video.",
            color = Cream, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        SheetAction(Icons.AutoMirrored.Filled.Undo, "How it works", "See the quick intro again", onClick = onHelp)
        if (FEEDBACK_EMAIL.isNotEmpty()) {
            SheetAction(Icons.Filled.SwapHoriz, "Send feedback", FEEDBACK_EMAIL) {
                val i = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:$FEEDBACK_EMAIL"))
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Demo-tape $version feedback")
                try {
                    ctx.startActivity(i)
                } catch (_: Exception) {
                }
            }
        }
    }
}
