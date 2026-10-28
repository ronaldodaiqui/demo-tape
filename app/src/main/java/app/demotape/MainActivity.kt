package app.demotape

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.demotape.ui.Bg
import app.demotape.ui.BusyDialog
import app.demotape.ui.DemoTapeTheme
import app.demotape.ui.SongListScreen
import app.demotape.ui.SongScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val micForIdea = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.quickCapture()
    }

    /** Starts a new recording right away, asking for the mic first if needed. */
    fun recordIdea() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.quickCapture()
        } else {
            micForIdea.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DemoTapeTheme { App(vm, onRecordIdea = { recordIdea() }) }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_NEW_IDEA) {
            intent.action = Intent.ACTION_MAIN // don't repeat it on rotation or relaunch
            recordIdea()
        }
    }

    companion object {
        const val ACTION_NEW_IDEA = "app.demotape.NEW_IDEA"
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.onBackground()
    }
}

@Composable
private fun App(vm: AppViewModel, onRecordIdea: () -> Unit) {
    val song by vm.song.collectAsState()
    val busy by vm.busy.collectAsState()
    // Large system font sizes are respected up to a point; beyond that the mixer and
    // transport wouldn't fit on a phone screen.
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, minOf(d.fontScale, 1.2f))
    ) {
        Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
            val s = song
            if (s == null) {
                SongListScreen(vm, onRecordIdea)
            } else {
                BackHandler { vm.closeSong() }
                SongScreen(vm, s)
            }
        }
    }
    busy?.let { BusyDialog(it) }
    CrashReport()
}

/** If the app crashed last time, shows what happened so it can be copied and sent. */
@Composable
private fun CrashReport() {
    val app = LocalContext.current.applicationContext as android.app.Application
    var report by remember {
        mutableStateOf(DemoTapeApp.crashFile(app).takeIf { it.exists() }?.readText())
    }
    val clipboard = LocalClipboardManager.current
    val text = report ?: return
    val close = {
        DemoTapeApp.crashFile(app).delete()
        report = null
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Demo-tape crashed last time") },
        text = {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                close()
            }) { Text("Copy details") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Close") } },
    )
}
