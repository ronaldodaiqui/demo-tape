package app.demotape.data

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class OutputKind { BLUETOOTH, WIRED, SPEAKER }

/** The headphones or speaker that playback is going to. [key] identifies it across sessions. */
data class OutputDevice(val key: String, val name: String, val kind: OutputKind)

/**
 * Per-output sync setting. [offset] is extra delay in frames, subtracted from where
 * new takes land, on top of what Android reports. [delayMs] is the round trip for display.
 */
data class SyncInfo(val offset: Long, val delayMs: Int, val synced: Boolean)

class SyncStore(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    val version = MutableStateFlow(0)

    fun get(key: String): SyncInfo? {
        val raw = prefs.getString(key, null) ?: return null
        val p = raw.split("|")
        if (p.size < 3) return null
        return SyncInfo(p[0].toLongOrNull() ?: 0L, p[1].toIntOrNull() ?: 0, p[2] == "synced")
    }

    fun put(key: String, info: SyncInfo) {
        prefs.edit()
            .putString(key, "${info.offset}|${info.delayMs}|${if (info.synced) "synced" else "skipped"}")
            .apply()
        version.value = version.value + 1
    }
}

/** Tracks which output media playback will be routed to, the way Android picks it. */
class Outputs(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val _current = MutableStateFlow(detect())
    val current: StateFlow<OutputDevice> = _current

    init {
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                _current.value = detect()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                _current.value = detect()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun isBluetooth(d: AudioDeviceInfo): Boolean {
        if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return true
        if (Build.VERSION.SDK_INT >= 31 &&
            (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        ) return true
        return false
    }

    private fun detect(): OutputDevice {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outs.firstOrNull { isBluetooth(it) }?.let { d ->
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth headphones"
            return OutputDevice("bt:$name", name, OutputKind.BLUETOOTH)
        }
        outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }?.let { d ->
            val name = if (d.type == AudioDeviceInfo.TYPE_USB_HEADSET)
                d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB headphones"
            else "Wired headphones"
            return OutputDevice("wired:$name", name, OutputKind.WIRED)
        }
        return OutputDevice("speaker", "Phone speaker", OutputKind.SPEAKER)
    }
}
