package app.demotape

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DemoTapeTheme { App(vm) }
        }
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
private fun App(vm: AppViewModel) {
    val song by vm.song.collectAsState()
    val busy by vm.busy.collectAsState()
    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        val s = song
        if (s == null) {
            SongListScreen(vm)
        } else {
            BackHandler { vm.closeSong() }
            SongScreen(vm, s)
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
