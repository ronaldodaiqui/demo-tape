package app.demotape.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF1A1714)
val Surface1 = Color(0xFF231F1B)
val Surface2 = Color(0xFF2D2823)
val LaneA = Color(0xFF201C18)
val LaneB = Color(0xFF1C1915)
val Cream = Color(0xFFE9DCC4)
val Muted = Color(0xFF9A8E7C)
val Faint = Color(0xFF5E554A)
val RecRed = Color(0xFFE5484D)
val MuteAmber = Color(0xFFE5A23B)
val Good = Color(0xFF7CC07A)
/** Section markers: a warm tape-label tone. */
val MarkerAmber = Color(0xFFD9B26F)

val TrackColors = listOf(
    Color(0xFFE8833A), // 1 orange
    Color(0xFF3FB0A8), // 2 teal
    Color(0xFFE5C04B), // 3 yellow
    Color(0xFFC47AC0), // 4 violet
)

private val scheme = darkColorScheme(
    primary = Cream,
    onPrimary = Bg,
    secondary = TrackColors[1],
    background = Bg,
    onBackground = Cream,
    surface = Surface1,
    onSurface = Cream,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    outline = Faint,
    error = RecRed,
)

@Composable
fun DemoTapeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

fun formatTime(frames: Long, sr: Int): String {
    val tenths = (frames.coerceAtLeast(0L) * 10 / sr)
    val s = tenths / 10
    return "%d:%02d.%d".format(s / 60, s % 60, tenths % 10)
}

fun formatLength(frames: Long, sr: Int): String {
    val s = frames.coerceAtLeast(0L) / sr
    return "%d:%02d".format(s / 60, s % 60)
}
