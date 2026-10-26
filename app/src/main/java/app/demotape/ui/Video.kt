package app.demotape.ui

import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * Tap a tile to make it bigger.
 */
@Composable
fun VideoTiles(
    song: Song,
    videoFile: (Clip) -> File,
    position: () -> Long,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val songNow by rememberUpdatedState(song)
    val posNow by rememberUpdatedState(position)
    // Only recomposes when the set of takes under the playhead changes.
    val active by remember {
        derivedStateOf {
            val p = posNow()
            songNow.visibleClips
                .filter { it.video && p >= it.pos && p < it.end }
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
                modifier = Modifier
                    .size(if (isBig) 180.dp else 72.dp, if (isBig) 240.dp else 96.dp)
                    .clickable { big = if (isBig) null else c.track },
            )
        }
    }
}

@Composable
private fun VideoTile(file: File, clip: Clip, position: () -> Long, playing: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val clipNow by rememberUpdatedState(clip)
    val posNow by rememberUpdatedState(position)
    // Follow the song clock: play along while playing (re-seeking if it drifts), scrub when paused.
    LaunchedEffect(player, playing) {
        while (true) {
            val c = clipNow
            val targetMs = ((posNow() - c.pos + c.videoOffset) * 1000 / SR).coerceAtLeast(0L)
            if (playing) {
                if (!player.playWhenReady) player.playWhenReady = true
                if (abs(player.currentPosition - targetMs) > 150) player.seekTo(targetMs)
                delay(250)
            } else {
                if (player.playWhenReady) player.playWhenReady = false
                if (abs(player.currentPosition - targetMs) > 30) player.seekTo(targetMs)
                delay(60)
            }
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, TrackColors[clip.track], RoundedCornerShape(10.dp))
            .background(Bg),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
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
