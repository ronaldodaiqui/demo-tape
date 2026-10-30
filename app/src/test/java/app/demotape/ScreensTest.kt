package app.demotape

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.demotape.audio.Drums
import app.demotape.audio.PeakBuilder
import app.demotape.audio.SR
import app.demotape.data.Clip
import app.demotape.data.Marker
import app.demotape.data.Song
import app.demotape.data.SongRepository
import app.demotape.data.TrackState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Pictures of the real app at a few phone sizes, with sample songs, for checking layouts.
 * Only runs when asked for (gradle -Pscreens); the pictures land in app/build/screens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreensTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val out = File("build/screens").apply { mkdirs() }

    private val sizes = listOf(
        "1-small" to "w320dp-h640dp-hdpi",
        "2-phone" to "w393dp-h852dp-xxhdpi",
        "3-big-phone" to "w430dp-h932dp-xxhdpi",
        "4-tablet" to "w800dp-h1280dp-mdpi",
    )

    @Test
    fun songList() = sizes.forEach { (name, q) ->
        launch(q) { shoot("list-$name") }
    }

    @Test
    fun songScreen() = sizes.forEach { (name, q) ->
        launch(q) { vm, ids ->
            vm.openSong(ids[0])
            shoot("song-$name")
        }
    }

    @Test
    fun bigText() {
        launch("w393dp-h852dp-xxhdpi", fontScale = 1.3f) { vm, ids ->
            shoot("list-5-big-text")
            vm.openSong(ids[0])
            shoot("song-5-big-text")
        }
        launch("w320dp-h640dp-hdpi", fontScale = 1.3f) { vm, ids ->
            shoot("list-6-small-big-text")
            vm.openSong(ids[0])
            shoot("song-6-small-big-text")
        }
    }

    @Test
    fun panels() = launch("w393dp-h852dp-xxhdpi") { vm, ids ->
        vm.openSong(ids[0])
        settle()
        tryShoot("panel-drums") {
            rule.onNodeWithText("Drums").performClick()
            rule.onNode(isDialog())
        }
        pressBack()
        tryShoot("panel-metronome") {
            rule.onNodeWithText("♩").performClick()
            rule.onNode(isDialog())
        }
        pressBack()
        tryShoot("panel-menu") {
            rule.onNodeWithContentDescription("More").performClick()
            null
        }
        tryShoot("page-help") {
            rule.onNodeWithText("Help").performClick()
            null
        }
        pressBack()
        tryShoot("dialog-mic") {
            vm.micNeeded.value = true
            rule.onNode(isDialog())
        }
    }

    @Test
    fun firstLaunch() = launch("w393dp-h852dp-xxhdpi", introSeen = false) { shoot("intro") }

    // ------------------------------------------------------------------ helpers

    private fun launch(
        qualifiers: String,
        fontScale: Float = 1f,
        introSeen: Boolean = true,
        body: (AppViewModel, List<String>) -> Unit,
    ) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "songs").deleteRecursively()
        app.getSharedPreferences("ui", 0).edit().putBoolean("intro_seen", introSeen).commit()
        val ids = seed(app)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var vm: AppViewModel? = null
            sc.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            settle()
            body(vm!!, ids)
        }
    }

    private fun launch(qualifiers: String, fontScale: Float = 1f, introSeen: Boolean = true, body: () -> Unit) =
        launch(qualifiers, fontScale, introSeen) { _, _ -> body() }

    private fun settle(ms: Long = 1500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeBy(50)
            Thread.sleep(20)
        }
    }

    private fun pressBack() {
        try {
            androidx.test.espresso.Espresso.pressBack()
        } catch (_: Throwable) {
        }
        settle(600)
    }

    private fun shoot(name: String, node: SemanticsNodeInteraction? = null) {
        settle()
        val bmp = (node ?: rule.onRoot()).captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Opens something and photographs it; a panel that can't be captured is skipped, not fatal. */
    private fun tryShoot(name: String, open: () -> SemanticsNodeInteraction?) {
        try {
            val node = open()
            shoot(name, node)
        } catch (t: Throwable) {
            File(out, "$name-FAILED.txt").writeText(t.toString())
        }
    }

    // ------------------------------------------------------------------ sample songs

    private fun seed(app: Application): List<String> {
        val repo = SongRepository(File(app.filesDir, "songs"))
        val beat = SR * 60.0 / 96
        val bar = beat * 4
        fun at(bars: Double) = (bars * bar).toLong()
        val now = System.currentTimeMillis()

        val a0 = repo.create("10-29-2026 21-45")
        val takes = listOf(
            Triple(0, at(4.0), strum(44.0, beat, 1)),
            Triple(1, at(8.0), voice(36.0, 2)),
            Triple(2, at(8.0), bass(30.0, beat, 3)),
            Triple(3, at(12.0), shaker(24.0, beat, 4)),
        )
        val clips = takes.mapIndexed { i, (track, start, pcm) -> writeTake(repo, a0.id, "a$i", track, start, pcm) }
        val a = a0.copy(
            bpm = 96, drumsOn = true, grid = true, drumBars = 1,
            drumRows = Drums.presets[0].second.map { Drums.tile(it, a0.copy(bpm = 96)) },
            clips = clips,
            tracks = listOf("Gtr", "Vox", "Bass", "Shaker").map { TrackState(name = it) },
            markers = listOf(
                Marker("m1", at(4.0), "Intro"), Marker("m2", at(8.0), "Verse"), Marker("m3", at(16.0), "Chorus"),
            ),
            notes = "G  D  Em  C\nwhen the night comes rolling in",
            modified = now,
        )
        repo.save(a)

        val b0 = repo.create("10-28-2026 19-02")
        val b = b0.copy(
            clips = listOf(
                writeTake(repo, b0.id, "b0", 0, (SR * 1.2).toLong(), strum(28.0, SR * 0.7, 5)),
                writeTake(repo, b0.id, "b1", 1, (SR * 6.0).toLong(), voice(20.0, 6)),
            ),
            notes = "slow one, capo 3",
            modified = now - 86_400_000L,
        )
        repo.save(b)

        val c0 = repo.create("10-27-2026 08-31")
        val c = c0.copy(clips = listOf(writeTake(repo, c0.id, "c0", 0, (SR * 3.0).toLong(), voice(14.0, 7))), modified = now - 2 * 86_400_000L)
        repo.save(c)

        val d0 = repo.create("Bridge idea")
        repo.save(d0.copy(modified = now - 3 * 86_400_000L))
        return listOf(a.id, b.id, c.id, d0.id)
    }

    private fun writeTake(repo: SongRepository, songId: String, id: String, track: Int, start: Long, pcm: ShortArray): Clip {
        val f = repo.clipFile(songId, id)
        f.parentFile!!.mkdirs()
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bb.putShort(it) }
        f.writeBytes(bb.array())
        repo.savePeaks(songId, id, PeakBuilder.fromSamples(java.nio.ShortBuffer.wrap(pcm)))
        return Clip(id, track, start, 0L, pcm.size.toLong(), System.currentTimeMillis(), "")
    }

    private fun rnd(seed: Int) = java.util.Random(seed.toLong())

    /** Strummed chords on every beat, accents on 1. */
    private fun strum(sec: Double, beat: Double, seed: Int): ShortArray {
        val r = rnd(seed)
        val n = (sec * SR).toInt()
        val out = ShortArray(n)
        val notes = doubleArrayOf(196.0, 247.0, 294.0, 392.0)
        var k = 0
        while (k * beat < n) {
            val s0 = (k * beat).toInt()
            val amp = if (k % 4 == 0) 0.55 else 0.32 + r.nextDouble() * 0.1
            for (i in 0 until (beat * 1.5).toInt()) {
                val j = s0 + i
                if (j >= n) break
                val t = i / SR.toDouble()
                var v = 0.0
                for (f in notes) v += sin(2 * PI * f * t)
                v = v / notes.size * amp * exp(-t / 0.35) + (r.nextDouble() - 0.5) * 0.02
                out[j] = (out[j] + v * 32767).toInt().coerceIn(-32767, 32767).toShort()
            }
            k++
        }
        return out
    }

    /** Sung phrases: a few seconds on, a breath off. */
    private fun voice(sec: Double, seed: Int): ShortArray {
        val r = rnd(seed)
        val n = (sec * SR).toInt()
        val out = ShortArray(n)
        var pos = 0
        while (pos < n) {
            val len = ((1.5 + r.nextDouble() * 2.5) * SR).toInt()
            val f0 = 180 + r.nextDouble() * 120
            for (i in 0 until len) {
                val j = pos + i
                if (j >= n) break
                val t = i / SR.toDouble()
                val env = sin(PI * i / len).let { it * it } * (0.4 + 0.25 * sin(2 * PI * 0.7 * t))
                val v = (sin(2 * PI * f0 * t) + 0.4 * sin(4 * PI * f0 * t)) * env * 0.6
                out[j] = (v * 32767).toInt().coerceIn(-32767, 32767).toShort()
            }
            pos += len + ((0.3 + r.nextDouble() * 0.6) * SR).toInt()
        }
        return out
    }

    private fun bass(sec: Double, beat: Double, seed: Int): ShortArray {
        val r = rnd(seed)
        val n = (sec * SR).toInt()
        val out = ShortArray(n)
        var k = 0
        while (k * beat < n) {
            val f = doubleArrayOf(98.0, 73.4, 82.4, 65.4)[(k / 4) % 4]
            val s0 = (k * beat).toInt()
            for (i in 0 until beat.toInt()) {
                val j = s0 + i
                if (j >= n) break
                val t = i / SR.toDouble()
                val v = sin(2 * PI * f * t) * exp(-t / 0.4) * (0.5 + r.nextDouble() * 0.05)
                out[j] = (v * 32767).toInt().toShort()
            }
            k++
        }
        return out
    }

    private fun shaker(sec: Double, beat: Double, seed: Int): ShortArray {
        val r = rnd(seed)
        val n = (sec * SR).toInt()
        val out = ShortArray(n)
        var k = 0
        val step = beat / 2
        while (k * step < n) {
            val s0 = (k * step).toInt()
            val amp = if (k % 2 == 0) 0.25 else 0.15
            for (i in 0 until (SR * 0.08).toInt()) {
                val j = s0 + i
                if (j >= n) break
                val t = i / SR.toDouble()
                out[j] = ((r.nextDouble() * 2 - 1) * amp * exp(-t / 0.02) * 32767).toInt().toShort()
            }
            k++
        }
        return out
    }
}
