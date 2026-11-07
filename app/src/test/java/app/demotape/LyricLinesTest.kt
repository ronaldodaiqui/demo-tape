package app.demotape

import app.demotape.ui.LyricLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class LyricLinesTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private val model by lazy {
        LyricLines.build(
            File(assets, "lyric_lines.txt").useLines { LyricLines.build(it) },
            File(assets, "sounds.txt").readText(),
        ) { w ->
            File(assets, "sounds_more.txt").bufferedReader().use { LyricLines.findSound(it, w) }
        }
    }

    private fun last(l: String) = l.split(' ').last().lowercase()

    @Test
    fun slantRhymesNotPerfectOnes() {
        val r = Random(5)
        val home = model.sound("home")!!
        val lines = (1..60).map { model.line("home", 8, r) }
        assertTrue("too few different lines", lines.toSet().size > 55)
        for (l in lines) {
            val s = model.sound(last(l))!!
            assertEquals("'$l' doesn't share home's vowel", home.vowels, s.vowels)
            assertTrue("'$l' is a perfect rhyme", s.tail != home.tail)
        }
        val near = lines.count { kotlin.math.abs(model.syllables(it.split(' ')) - 8) <= 2 }
        assertTrue("only $near of 60 near 8 syllables", near >= 50)
    }

    @Test
    fun versesRhymeEveryOtherLine() {
        // Line 3 answers line 1, line 4 answers line 2; a blank line starts over.
        val (r1, len1) = LyricLines.context(model, listOf("I drove all night", "with the windows down"))
        assertEquals("night", r1)
        assertEquals(5, len1)
        val (r2, _) = LyricLines.context(model, listOf("a", "I drove all night", "", "new verse here"))
        assertEquals(null, r2)
        // Chord rows don't count as lines.
        val (r3, _) = LyricLines.context(model, listOf("G   C   D", "we were never coming home", "Em  C", "hello there"))
        assertEquals("home", r3)
    }

    @Test
    fun insertsUnderTheCursorLine() {
        assertEquals("one\nNEW\ntwo" to 7, LyricLines.insert("one\ntwo", 2, "NEW"))
        assertEquals("one\nNEW" to 7, LyricLines.insert("one\n", 4, "NEW"))
        assertEquals("NEW" to 3, LyricLines.insert("", 0, "NEW"))
        assertEquals(listOf("one"), LyricLines.linesBefore("one\n", 4))
        assertEquals(listOf("one", ""), LyricLines.linesBefore("one\n\n", 5))
    }
}
