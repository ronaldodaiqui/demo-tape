package app.demotape

import app.demotape.ui.SongNames
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SongNamesTest {
    @Test
    fun namesAreVariedAndTidy() {
        val r = Random(7)
        val names = List(300) { SongNames.random(r) }
        assertTrue("too repetitive", names.toSet().size > 250)
        names.forEach { n ->
            assertTrue("odd name: '$n'", n.isNotBlank() && n == n.trim() && !n.contains("  ") && n.length <= 40)
        }
    }
}
