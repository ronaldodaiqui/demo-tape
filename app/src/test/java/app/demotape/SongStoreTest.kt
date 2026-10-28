package app.demotape

import app.demotape.data.Clip
import app.demotape.data.SongRepository
import app.demotape.data.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class SongStoreTest {
    private fun tmp(): File = Files.createTempDirectory("demotape").toFile()

    @Test
    fun songSurvivesSaveAndLoad() {
        val repo = SongRepository(tmp())
        val s0 = repo.create("Test")
        val s = s0.copy(
            bpm = 87, beatsPerBar = 3, click = true, notes = "G  C  D\\nwords",
            tracks = List(4) { TrackState(volume = 0.5f + it * 0.1f, name = "T$it", solo = it == 2, low = 3f) },
            clips = listOf(Clip("abc", 1, 4800, -12, 96000, 5, "bt:Buds", 480, gainDb = 6f, video = true, videoOffset = 100)),
        )
        repo.save(s)
        val back = repo.load(s.id)!!
        assertEquals(s.copy(modified = back.modified), back)
    }

    @Test
    fun backupRoundTripMakesANewSongWithTheSameContent() {
        val repo = SongRepository(tmp())
        val s = repo.create("Backup me").copy(notes = "hello", bpm = 120)
        repo.save(s)
        repo.clipFile(s.id, "c1").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4000) { it.toByte() }) }
        val withClip = s.copy(clips = listOf(Clip("c1", 0, 0, 0, 2000, 1, "")))
        repo.save(withClip)

        val out = ByteArrayOutputStream()
        repo.exportBackup(s.id, out)
        val imported = repo.importBackup(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(imported)
        imported!!
        assertTrue(imported.id != s.id)
        assertEquals("hello", imported.notes)
        assertEquals(120, imported.bpm)
        assertEquals(4000L, repo.clipFile(imported.id, "c1").length())
    }

    @Test
    fun importRejectsFilesThatArentBackups() {
        val repo = SongRepository(tmp())
        assertNull(repo.importBackup(ByteArrayInputStream("not a zip".toByteArray())))
    }

    @Test
    fun cleanupKeepsOnlyFilesTheSongUses() {
        val repo = SongRepository(tmp())
        val s = repo.create("Clean").copy(clips = listOf(Clip("keep", 0, 0, 0, 10, 1, "")))
        repo.save(s)
        repo.clipFile(s.id, "keep").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(20)) }
        repo.clipFile(s.id, "gone").writeBytes(ByteArray(20))
        repo.cleanup(s)
        assertTrue(repo.clipFile(s.id, "keep").exists())
        assertTrue(!repo.clipFile(s.id, "gone").exists())
    }
}
