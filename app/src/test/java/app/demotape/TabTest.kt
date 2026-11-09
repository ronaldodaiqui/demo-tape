package app.demotape

import app.demotape.ui.Tab
import app.demotape.ui.Tab.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabTest {
    private val cols = 30

    /** Runs keys from [start], checking after each that every string is still the same length. */
    private fun play(text: String, cursor: Int, vararg keys: Key): Pair<String, Int> {
        var t = text
        var c = cursor
        for (k in keys) {
            val r = Tab.key(t, c, k, cols)
            assertNotNull("cursor left the staff before $k", r)
            t = r!!.first
            c = r.second
            for (b in Tab.blocks(t)) {
                val lens = (0 until b.strings).map { b.lineEnds[it] - b.bodyStarts[it] }
                assertEquals("strings out of line after $k:\n$t", 1, lens.toSet().size)
            }
        }
        return t to c
    }

    private fun staffLines(t: String) = t.lines().filter { Tab.isStaffLine(it) }

    @Test
    fun templateGoesInAndIsFound() {
        val (t, c) = Tab.insertTemplate("Verse\n", 6, cols)
        val lines = t.lines()
        assertEquals("Verse", lines[0])
        assertEquals(6, staffLines(t).size)
        assertEquals("e|" + "-".repeat(27) + "|", lines[1])
        assertEquals(1, Tab.blocks(t).size)
        val sp = Tab.spot(t, c)!!
        assertEquals(0, sp.row)
        assertEquals(1, sp.col)
    }

    @Test
    fun fretsOverwriteAndTwoDigitsWidenTheColumn() {
        val (t0, c0) = Tab.insertTemplate("", 0, cols)
        // A chord: 3 on the top string, 12 on the next one down (1 then 2), same beat.
        var (t, c) = play(t0, c0, Key.Digit(3), Key.Down, Key.Digit(1), Key.Digit(2, combine = true))
        val l = staffLines(t)
        assertTrue(l[0], l[0].startsWith("e|-3"))
        assertTrue(l[1], l[1].startsWith("B|-12"))
        // Lengths unchanged: overwriting, not pushing.
        assertEquals(30, l[0].length)
        // Then a note after the 3: moves on a beat, still lined up.
        // One step right then a 5: it keeps a gap from the 3, and the 12 below stays whole.
        val r = play(t, c, Key.Up, Key.Right, Key.Digit(5), Key.Mark('h'), Key.Digit(7))
        t = r.first
        assertTrue(staffLines(t)[0], staffLines(t)[0].startsWith("e|-3-5h7"))
        assertTrue(staffLines(t)[1], staffLines(t)[1].startsWith("B|-12-"))
    }

    @Test
    fun twoDigitFretMakesRoomWhenTheNextBeatIsTaken() {
        val text = "e|-3-5----------|\nB|-------------|\nG|-------------|\nD|-------------|\nA|-------------|\nE|-------------|"
        // Turn the 3 into 13 though a 5 sits right after it: everything shifts right together.
        val at = text.indexOf('3')
        val (t, _) = play(text, at, Key.Digit(1), Key.Right, Key.Left)
        val (t2, _) = play(text, at, Key.Digit(1), Key.Digit(3, combine = true))
        assertTrue(staffLines(t2)[0], staffLines(t2)[0].startsWith("e|-13-5"))
        assertTrue(t.isNotEmpty())
    }

    @Test
    fun clearAndBeats() {
        val text = Tab.template(cols)
        val start = text.indexOf('|') + 2
        var (t, c) = play(text, start, Key.Digit(7), Key.Right, Key.Digit(9))
        // Insert a beat before the 9, then take it back out.
        val (t2, c2) = play(t, c, Key.AddBeat)
        assertTrue(staffLines(t2)[0], staffLines(t2)[0].startsWith("e|-7----9"))
        val (t3, _) = play(t2, c2, Key.RemoveBeat)
        assertTrue(staffLines(t3)[0], staffLines(t3)[0].startsWith("e|-7-9"))
        // Clearing the 9 leaves dashes.
        val (t4, _) = play(t, c, Key.Clear)
        assertTrue(staffLines(t4)[0], staffLines(t4)[0].startsWith("e|-7------"))
    }

    @Test
    fun runningOffTheEndStartsANewStaff() {
        var (t, c) = Tab.insertTemplate("", 0, cols)
        var n = 0
        while (Tab.blocks(t).size < 2 && n++ < 100) {
            val r = play(t, c, Key.Right)
            t = r.first
            c = r.second
        }
        assertEquals(2, Tab.blocks(t).size)
        assertTrue("cursor should be in the second staff", c >= Tab.blocks(t)[1].start)
        // And back again.
        val (t2, c2) = play(t, c, Key.Left)
        assertTrue("cursor should be back in the first staff", c2 <= Tab.blocks(t2)[0].end)
    }

    @Test
    fun pastedTabIsFoundAndChordRowsAreNot() {
        val note = "G   C   D\nsome words\n\ne|-----0---|\nB|---1-----|\nG|-0-------|\nD|---------|\nA|-3-------|\nE|---------|\n"
        val bs = Tab.blocks(note)
        assertEquals(1, bs.size)
        assertEquals(6, bs[0].strings)
    }

    @Test
    fun beatsNeverPushPastTheScreen() {
        val (t0, c0) = Tab.insertTemplate("", 0, cols)
        var t = t0
        var c = c0
        repeat(20) {
            val r = play(t, c, Key.AddBeat)
            t = r.first
            c = r.second
        }
        assertTrue(staffLines(t).all { it.length <= cols })
        // Fill it to the end, then +beat can't make room: nothing changes.
        var line = Tab.blocks(t)[0]
        var cc = line.bodyStarts[0]
        while (cc < line.lineEnds[0] - 2) {
            val r = play(t, cc, Key.Digit(1))
            t = r.first
            cc += 2
            line = Tab.blocks(t)[0]
        }
        val (same, _) = play(t, line.bodyStarts[0] + 1, Key.AddBeat)
        assertEquals(t, same)
    }

    @Test
    fun deleteTakesTheWholeStaff() {
        val note = "verse words\n\n" + Tab.template(cols) + "\n\nchorus words"
        val at = note.indexOf("e|") + 3
        val (t, _) = Tab.key(note, at, Key.DeleteStaff, cols)!!
        assertEquals("verse words\n\nchorus words", t)
        assertEquals(0, Tab.blocks(t).size)
    }
}
