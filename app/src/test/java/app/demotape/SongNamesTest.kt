package app.demotape

import app.demotape.ui.SongNames
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class SongNamesTest {
    @Test
    fun namesAreVariedAndTidy() {
        val m = SongNames.fromText(File("src/main/assets/title_words.txt").readText())
        val r = Random(7)
        val names = List(400) { SongNames.random(m, r) }
        assertTrue("too repetitive", names.toSet().size > 380)
        names.forEach { n ->
            assertTrue("odd name: '$n'", n.isNotBlank() && n == n.trim() && !n.contains("  ") && n.length <= 34)
            assertTrue("starts small: '$n'", n.first().isUpperCase())
        }
    }
}
