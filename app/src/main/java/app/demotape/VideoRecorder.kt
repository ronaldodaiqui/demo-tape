package app.demotape

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Films silent, low-res video alongside a take, just enough to see what you played.
 * Audio stays with the app's own mic recording; the camera never touches the mic.
 */
class VideoRecorder(private val context: Context) {
    val front = MutableStateFlow(true)
    val bound = MutableStateFlow(false)

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var view: PreviewView? = null
    private val preview = Preview.Builder().build()
    private val capture = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .setTargetVideoEncodingBitRate(600_000)
            .build()
    )

    private var recording: Recording? = null
    private var finalized: CompletableDeferred<Boolean>? = null
    private val starts = ArrayList<Long>()

    suspend fun bind(owner: LifecycleOwner, view: PreviewView) {
        this.owner = owner
        this.view = view
        rebind()
    }

    suspend fun flip() {
        if (recording != null) return
        front.value = !front.value
        rebind()
    }

    private suspend fun rebind() {
        val o = owner ?: return
        val v = view ?: return
        try {
            val p = provider ?: withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
            provider = p
            p.unbindAll()
            preview.setSurfaceProvider(v.surfaceProvider)
            val selector = if (front.value) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val sel = if (p.hasCamera(selector)) selector else CameraSelector.DEFAULT_BACK_CAMERA
            p.bindToLifecycle(o, sel, preview, capture)
            bound.value = true
        } catch (e: Exception) {
            Log.e("DemoTape", "camera bind failed", e)
            bound.value = false
        }
    }

    fun unbind() {
        try {
            provider?.unbindAll()
        } catch (_: Exception) {
        }
        bound.value = false
        owner = null
        view = null
    }

    /** Starts filming into [file]. Returns false if the camera isn't ready. */
    @SuppressLint("MissingPermission")
    fun start(file: File): Boolean {
        if (!bound.value || recording != null) return false
        starts.clear()
        val done = CompletableDeferred<Boolean>()
        finalized = done
        return try {
            recording = capture.output
                .prepareRecording(context, FileOutputOptions.Builder(file).build())
                .start(ContextCompat.getMainExecutor(context)) { ev ->
                    when (ev) {
                        is VideoRecordEvent.Status -> {
                            // When video time 0 happened on the system clock.
                            if (starts.size < 15) starts.add(System.nanoTime() - ev.recordingStats.recordedDurationNanos)
                        }
                        is VideoRecordEvent.Finalize -> done.complete(!ev.hasError())
                        else -> {}
                    }
                }
            true
        } catch (e: Exception) {
            Log.e("DemoTape", "video start failed", e)
            recording = null
            false
        }
    }

    /** Stops filming. Returns the System.nanoTime() of the video's first frame, or null on failure. */
    suspend fun stop(): Long? {
        val r = recording ?: return null
        recording = null
        r.stop()
        val ok = withTimeoutOrNull(4000) { finalized?.await() } ?: false
        if (!ok || starts.isEmpty()) return null
        val sorted = starts.sorted()
        return sorted[sorted.size / 2]
    }

    val isRecording get() = recording != null
}
