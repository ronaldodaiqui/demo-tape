package app.demotape

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.os.Looper
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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

    /**
     * Everything in one go: coroutines on the main thread only keep working within the first
     * test of a run here, so the shots aren't split across tests.
     */
    @Test
    fun allScreens() {
        sizes.forEach { (name, q) ->
            launch(q) { vm, ids ->
                tryShoot("list-$name") {}
                open(vm, ids[0])
                tryShoot("song-$name") {}
            }
        }
        launch("w393dp-h852dp-xxhdpi", fontScale = 1.3f) { vm, ids ->
            tryShoot("list-5-big-text") {}
            open(vm, ids[0])
            tryShoot("song-5-big-text") {}
        }
        launch("w320dp-h640dp-hdpi", fontScale = 1.3f) { vm, ids ->
            tryShoot("list-6-small-big-text") {}
            open(vm, ids[0])
            tryShoot("song-6-small-big-text") {}
        }
        launch("w393dp-h852dp-xxhdpi") { vm, ids ->
            open(vm, ids[0])
            tryShoot("panel-drums") { rule.onNodeWithText("Drums").performClick() }
            pressBack()
            tryShoot("panel-metronome") { rule.onNodeWithContentDescription("Metronome").performClick() }
            pressBack()
            tryShoot("panel-menu") { rule.onNodeWithContentDescription("More").performClick() }
            tryShoot("page-help") { rule.onNodeWithText("Help").performClick() }
            pressBack()
            pressBack()
            tryShoot("dialog-mic") { vm.micNeeded.value = true }
        }
        launch("w393dp-h852dp-xxhdpi", introSeen = false) { _, _ -> tryShoot("intro") {} }
    }

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

    /** Opens a song and waits until its screen is showing. */
    private fun open(vm: AppViewModel, id: String) {
        vm.openSong(id)
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && vm.song.value?.id != id) settle(200)
        settle(1500)
    }

    /**
     * Draws every window the app has open (the screen, plus any panel, menu or dialog on top)
     * into one picture, the way it would look on the phone.
     */
    private fun shoot(name: String) {
        settle()
        val roots = rootViews().filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
        if (roots.isEmpty()) error("no windows")
        val base = roots.first()
        val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        roots.forEachIndexed { i, v ->
            val lp = v.layoutParams as? WindowManager.LayoutParams
            var x = 0
            var y = 0
            if (i > 0 && lp != null) {
                if (lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    c.drawColor(android.graphics.Color.argb((lp.dimAmount * 255).toInt(), 0, 0, 0))
                }
                val g = lp.gravity
                val centered = g == Gravity.CENTER || g == 0
                x = if (centered && v.width < base.width) (base.width - v.width) / 2 else lp.x
                y = when {
                    centered && v.height < base.height -> (base.height - v.height) / 2
                    g and Gravity.BOTTOM == Gravity.BOTTOM -> base.height - v.height
                    else -> lp.y
                }
            }
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            v.draw(c)
            c.restore()
        }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val f = wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        return ArrayList(f.get(wmg) as List<View>)
    }

    /** Opens something and photographs it; if that fails, notes why (and what was on screen). */
    private fun tryShoot(name: String, open: () -> Unit) {
        try {
            open()
            shoot(name)
        } catch (t: Throwable) {
            val tree = try {
                rule.onAllNodes(isRoot()).printToString(maxDepth = 6)
            } catch (e: Throwable) {
                e.toString()
            }
            File(out, "$name-FAILED.txt").writeText(t.toString() + "\n\n" + tree)
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
            Triple(0, at(0.5), strum(44.0, beat, 1)),
            Triple(1, at(1.0), voice(36.0, 2)),
            Triple(2, at(1.0), bass(30.0, beat, 3)),
            Triple(3, at(2.0), shaker(24.0, beat, 4)),
        )
        val clips = takes.mapIndexed { i, (track, start, pcm) -> writeTake(repo, a0.id, "a$i", track, start, pcm) }
        val a = a0.copy(
            bpm = 96, drumsOn = true, grid = true, drumBars = 1,
            drumRows = Drums.presets[0].second.map { Drums.tile(it, a0.copy(bpm = 96)) },
            clips = clips,
            tracks = listOf("Gtr", "Vox", "Bass", "Shaker").map { TrackState(name = it) },
            markers = listOf(
                Marker("m1", at(0.5), "Intro"), Marker("m2", at(1.0), "Verse"), Marker("m3", at(2.0), "Chorus"),
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
