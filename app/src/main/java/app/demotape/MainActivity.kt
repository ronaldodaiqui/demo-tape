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
import androidx.compose.ui.Modifier
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
}
