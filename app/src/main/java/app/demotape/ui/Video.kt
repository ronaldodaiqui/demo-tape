package app.demotape.ui

import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.demotape.VideoRecorder
import app.demotape.audio.SR
import app.demotape.data.Clip
import app.demotape.data.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/** Live camera view while video mode is on, so you can frame yourself. */
@Composable
fun CameraTile(recorder: VideoRecorder, recording: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    LaunchedEffect(view) { recorder.bind(ctx as LifecycleOwner, view) }
    DisposableEffect(view) { onDispose { if (!recorder.isRecording) recorder.unbind() } }
    Box(
        modifier
            .size(width = 96.dp, height = 128.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, if (recording) RecRed else Cream.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .background(Bg),
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (!recording) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Bg.copy(alpha = 0.6f))
                    .clickable { scope.launch { recorder.flip() } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Flip camera", tint = Cream, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Video of the takes under the playhead, one tile per track that has some.
 * Tap a tile to make it bigger; hold one for its options ([onHold]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoTiles(
    song: Song,
    videoFile: (Clip) -> File,
    position: () -> Long,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onHold: (Clip) -> Unit = {},
) {
    val songNow by rememberUpdatedState(song)
    val posNow by rememberUpdatedState(position)
    // Only recomposes when the set of takes under the playhead changes.
    val active by remember {
        derivedStateOf {
            val p = posNow()
            songNow.visibleClips
                .filter { it.showsVideo && p >= it.pos && p < it.end }
                .groupBy { it.track }
                .map { (_, cs) -> cs.maxBy { it.created } }
                .sortedBy { it.track }
        }
    }
    var big by remember { mutableStateOf<Int?>(null) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in active) {
            val f = videoFile(c)
            if (!f.exists()) continue
            val isBig = big == c.track
            VideoTile(
                file = f,
                clip = c,
                position = position,
                playing = playing,
                big = isBig,
                modifier = Modifier.combinedClickable(
                    onClick = { big = if (isBig) null else c.track },
                    onLongClick = { onHold(c) },
                ),
            )
        }
    }
}

@Composable
private fun VideoTile(file: File, clip: Clip, position: () -> Long, playing: Boolean, big: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    // The window takes the video's shape (after any turn), so a video filmed sideways gets a
    // wide window and nothing is cut off.
    var wide by remember(file) { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            @Suppress("DEPRECATION")
            override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) {
                if (vs.width <= 0 || vs.height <= 0) return
                val sideways = vs.unappliedRotationDegrees % 180 != 0
                wide = if (sideways) vs.height > vs.width else vs.width > vs.height
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }
    val turned = clip.videoRotation % 180 != 0
    val landscape = wide != turned
    val long = if (big) 240.dp else 96.dp
    val short = if (big) 180.dp else 72.dp
    val clipNow by rememberUpdatedState(clip)
    val posNow by rememberUpdatedState(position)
    // Follow the song clock: scrub along when paused; when playing, start together with the
    // sound and stay with it.
    LaunchedEffect(player, playing) {
        fun targetMs(): Long {
            val c = clipNow
            return ((posNow() - c.pos + c.videoOffset) * 1000 / SR).coerceAtLeast(0L)
        }
        fun speed(v: Float) {
            if (player.playbackParameters.speed != v) player.setPlaybackSpeed(v)
        }
        if (!playing) {
            speed(1f)
            while (true) {
                if (player.playWhenReady) player.playWhenReady = false
                val t = targetMs()
                if (abs(player.currentPosition - t) > 30) player.seekTo(t)
                delay(60)
            }
        }
        // The song clock holds still until the sound actually reaches the speaker or headphones
        // (a moment, longer on Bluetooth). Starting the video before that put it ahead, and the
        // seeks to fix it made it stutter. So wait for the clock to move, then start from the
        // frame it's already sitting on (kept there while paused).
        val p0 = posNow()
        var waited = 0
        while (posNow() == p0 && waited < 1500) {
            delay(10)
            waited += 10
        }
        targetMs().let { if (abs(player.currentPosition - it) > 30) player.seekTo(it) }
        speed(1f)
        player.playWhenReady = true
        var settleUntil = System.currentTimeMillis() + 500
        while (true) {
            delay(100)
            val t = targetMs()
            val now = System.currentTimeMillis()
            // Just started or just jumped: let it get going before judging it.
            if (player.playbackState != Player.STATE_READY || now < settleUntil) continue
            val off = player.currentPosition - t // + = video ahead
            when {
                // Far off (a loop came round, or a jump): go straight there.
                abs(off) > 500 -> {
                    speed(1f)
                    player.seekTo(t)
                    settleUntil = now + 600
                }
                // A little off: run a touch faster or slower to drift back in, no jump.
                abs(off) > 40 -> speed((1f - off / 1000f).coerceIn(0.75f, 1.25f))
                else -> speed(1f)
            }
        }
    }
    Box(
        modifier
            .size(if (landscape) long else short, if (landscape) short else long)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, TrackColors[clip.track], RoundedCornerShape(10.dp))
            .background(Bg),
    ) {
        // Turned by the take's rotation: for a quarter turn the player is laid out with width and
        // height swapped, then rotated, so it still fills the tile.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val rot = clip.videoRotation
            val swap = rot % 180 != 0
            AndroidView(
                factory = {
                    (android.view.LayoutInflater.from(it).inflate(app.demotape.R.layout.video_tile, null) as PlayerView).apply {
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier
                    .align(Alignment.Center)
                    .requiredSize(if (swap) maxHeight else maxWidth, if (swap) maxWidth else maxHeight)
                    .graphicsLayer { rotationZ = rot.toFloat() },
            )
        }
        Text(
            "${clip.track + 1}",
            color = TrackColors[clip.track], fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Bg.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp),
        )
    }
}
