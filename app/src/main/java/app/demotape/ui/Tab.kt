package app.demotape.ui

/**
 * Guitar tab inside a song's notes.
 *
 * The note stays plain text: a tab staff is ordinary tab, one line per string, each starting
 * with the string's name and a bar ("e|--3--5--|"). Four to eight such lines in a row make a
 * staff (so tab pasted in from anywhere works too). The notes page draws each staff in its own
 * box and edits it with a fret pad instead of the keyboard.
 *
 * Every edit works on whole columns across all the strings, so the staff can't fall out of
 * line: a number overwrites the dashes under it, a two-digit fret widens its column for every
 * string, clearing turns a note back into dashes, and adding or removing a beat adds or removes
 * the same columns on every string.
 */
object Tab {
    /** Standard tuning, high string first, as tab is written. */
    val STANDARD = listOf("e", "B", "G", "D", "A", "E")

    private val LINE = Regex("^\\s*([A-Ga-g][#b]?)\\s*\\|([-0-9hpbrxX/\\\\~^().|*=svtHPBRSVT ]*)$")

    /** A string line of tab (name, bar, then mostly dashes and frets). */
    fun isStaffLine(l: String): Boolean {
        val m = LINE.matchEntire(l) ?: return false
        return m.groupValues[2].count { it == '-' } >= 4
    }

    /** One staff in the note: where each string's line starts and ends, and where its frets start. */
    class Block(val lineStarts: IntArray, val lineEnds: IntArray, val bodyStarts: IntArray) {
        val start: Int get() = lineStarts.first()
        val end: Int get() = lineEnds.last()
        val strings: Int get() = lineStarts.size
        operator fun contains(offset: Int) = offset in start..end
    }

    /** The staves in [text], in order. */
    fun blocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        val run = ArrayList<Triple<Int, Int, Int>>() // line start, line end, body start
        var name: String? = null
        fun flush() {
            if (run.size in 4..8) {
                out += Block(
                    run.map { it.first }.toIntArray(),
                    run.map { it.second }.toIntArray(),
                    run.map { it.third }.toIntArray(),
                )
            }
            run.clear()
            name = null
        }
        var s = 0
        while (s <= text.length) {
            val e = text.indexOf('\n', s).let { if (it < 0) text.length else it }
            val l = text.substring(s, e)
            if (isStaffLine(l)) {
                val n = l.trim().substringBefore('|').trim()
                // Two staves with no blank line between: the top string's name comes round again.
                if (run.size >= 4 && n == name) flush()
                if (run.isEmpty()) name = n
                run += Triple(s, e, s + l.indexOf('|') + 1)
            } else {
                flush()
            }
            if (e == text.length) break
            s = e + 1
        }
        flush()
        return out
    }

    /** Where [offset] falls in the staves: the block, the string (0 = top) and the column. */
    data class Spot(val block: Block, val row: Int, val col: Int)

    fun spot(text: String, offset: Int): Spot? {
        val b = blocks(text).firstOrNull { offset in it } ?: return null
        val row = (0 until b.strings).firstOrNull { offset <= b.lineEnds[it] } ?: (b.strings - 1)
        val g = Grid.load(text, b)
        val col = (offset - b.bodyStarts[row]).coerceIn(0, (g.width - 1).coerceAtLeast(0))
        return Spot(b, row, col)
    }

    /** A blank staff [cols] characters wide (name and bars included). */
    fun template(cols: Int, names: List<String> = STANDARD): String {
        val ns = padNames(names)
        val dashes = (cols - 2 - ns[0].length).coerceAtLeast(12)
        return ns.joinToString("\n") { "$it|" + "-".repeat(dashes) + "|" }
    }

    /** String names padded to the same width (Eb, A → "Eb", "A "), so the frets still line up. */
    private fun padNames(names: List<String>): List<String> {
        val t = names.map { it.trim() }
        val w = t.maxOf { it.length }
        return t.map { it.padEnd(w) }
    }

    // ---------------------------------------------------------------- tunings

    /** A tuning: its name, and the strings low to high, as people say them (D A D G A D). */
    data class Tuning(val name: String, val lowToHigh: List<String>) {
        /** The string names as tab writes them: high string first, the top one lowercase if
         * its letter comes round again lower down (e … E, d … D). */
        val names: List<String>
            get() {
                val hi = lowToHigh.reversed().toMutableList()
                val top = hi[0]
                if (hi.drop(1).any { it.equals(top, ignoreCase = true) }) hi[0] = top.lowercase()
                else if (top.length == 1 && top == "E") hi[0] = "e"
                return hi
            }
        val spoken: String get() = lowToHigh.joinToString(" ")
    }

    val TUNINGS = listOf(
        Tuning("Standard", listOf("E", "A", "D", "G", "B", "E")),
        Tuning("Drop D", listOf("D", "A", "D", "G", "B", "E")),
        Tuning("DADGAD", listOf("D", "A", "D", "G", "A", "D")),
        Tuning("Open G", listOf("D", "G", "D", "G", "B", "D")),
        Tuning("Open D", listOf("D", "A", "D", "F#", "A", "D")),
        Tuning("Half step down", listOf("Eb", "Ab", "Db", "Gb", "Bb", "Eb")),
        Tuning("Bass", listOf("E", "A", "D", "G")),
    )

    /** Reads a typed tuning, low to high ("D A D G B E", "DADGBE", "C# F# B"), or null if it isn't one. */
    fun parseTuning(typed0: String): List<String>? {
        var typed = typed0.replace("♯", "#").replace("♭", "b")
        // Run together in lowercase ("dgdgbd"), a b is a string, not a flat.
        if (!typed.contains(Regex("[\\s,/-]")) && typed == typed.lowercase()) typed = typed.uppercase()
        val notes = Regex("[A-Ga-g][#b]?").findAll(typed).map { m ->
            m.value.replaceFirstChar { it.uppercaseChar() }
        }.toList()
        val rest = typed.replace(Regex("[A-Ga-g][#b♯♭]?|[\\s,/-]"), "")
        return if (notes.size in 4..8 && rest.isEmpty()) notes else null
    }

    /** The cursor is on a staff's string names (left of the bar), where a tap picks the tuning. */
    fun onNames(text: String, offset: Int): Boolean {
        val b = blocks(text).firstOrNull { offset in it } ?: return false
        val row = (0 until b.strings).firstOrNull { offset <= b.lineEnds[it] } ?: return false
        return offset < b.bodyStarts[row]
    }

    /** The string names of the staff at [offset], low to high, trimmed (e.g. D A D G A D). */
    fun tuningAt(text: String, offset: Int): List<String>? {
        val b = blocks(text).firstOrNull { offset in it } ?: return null
        return (0 until b.strings).map { text.substring(b.lineStarts[it], b.bodyStarts[it] - 1).trim() }.reversed()
    }

    /** Which of [TUNINGS] the staff at [offset] is in, if any. */
    fun knownTuning(text: String, offset: Int): Tuning? {
        val t = tuningAt(text, offset) ?: return null
        return TUNINGS.firstOrNull { k -> k.lowToHigh.size == t.size && k.lowToHigh.zip(t).all { (a, b) -> a.equals(b, ignoreCase = true) } }
    }

    /**
     * Retunes the staff at [offset]: its string names become [lowToHigh]. Changing how many
     * strings there are adds or takes away strings at the top (guitar to bass keeps the low four,
     * with what's written on them). Returns the new text and a cursor at the start of the same string.
     */
    fun retune(text: String, offset: Int, lowToHigh: List<String>): Pair<String, Int>? {
        val b = blocks(text).firstOrNull { offset in it } ?: return null
        val g = Grid.load(text, b)
        val row = (0 until b.strings).firstOrNull { offset <= b.lineEnds[it] } ?: 0
        val names = Tuning("", lowToHigh).names
        val rows = g.rows.map { StringBuilder(it) }.toMutableList()
        val w = g.width
        while (rows.size > names.size) rows.removeAt(0)
        while (rows.size < names.size) rows.add(0, StringBuilder("-".repeat(w)))
        val ng = Grid(padNames(names), rows, g.closing)
        val t = text.substring(0, b.start) + ng.render() + text.substring(b.end)
        // Same string (counted from the bottom, which stays put).
        val fromBottom = b.strings - 1 - row
        val r = (names.size - 1 - fromBottom).coerceIn(0, names.size - 1)
        var off = b.start
        for (i in 0 until r) off += ng.lineLength(i) + 1
        off += ng.names[r].length + 1
        return t to off
    }

    /**
     * Puts a blank staff into [text] at [cursor]: on the cursor's line if it's empty, else on a
     * new line under it (under the whole staff if the cursor is in one). Returns the new text and
     * a cursor on the first beat of the top string.
     */
    fun insertTemplate(text: String, cursor: Int, cols: Int): Pair<String, Int> {
        val inBlock = blocks(text).firstOrNull { cursor in it }
        val c = inBlock?.end ?: cursor.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', c - 1) + 1
        val end = text.indexOf('\n', c).let { if (it < 0) text.length else it }
        // Same tuning as the staff it's in, or else the nearest one above it.
        val like = inBlock ?: blocks(text).lastOrNull { it.end < c }
        val staff = template(cols, like?.let { Grid.load(text, it).names } ?: STANDARD)
        val (t, c0) = if (inBlock == null && text.substring(start, end).isBlank()) {
            val t = text.substring(0, start) + staff + text.substring(end)
            t to start + staff.indexOf('|') + 2
        } else {
            val gap = if (inBlock != null) "\n\n" else "\n"
            val t = text.substring(0, end) + gap + staff + text.substring(end)
            val at = end + gap.length
            t to at + staff.indexOf('|') + 2
        }
        return roomAround(t, c0)
    }

    /**
     * Makes sure there's a line to write on above and below every staff: one at the very top or
     * bottom of the note would otherwise leave nowhere to tap to get back to writing text.
     */
    fun roomAround(text: String, cursor: Int): Pair<String, Int> {
        var t = text
        var c = cursor
        val bs = blocks(t)
        if (bs.isEmpty()) return t to c
        if (bs.first().start == 0) {
            t = "\n" + t
            c++
        }
        if (blocks(t).last().end >= t.length) t += "\n"
        return t to c
    }

    /** What the fret pad can do. */
    sealed class Key {
        /** A fret number. [combine]: right after another digit on the same spot (1 then 2 = 12). */
        data class Digit(val d: Int, val combine: Boolean = false) : Key()

        /** A technique mark after the note (h p / \ b ~), or x for a muted string. */
        data class Mark(val c: Char) : Key()
        object Clear : Key()
        object Left : Key()
        object Right : Key()
        object Up : Key()
        object Down : Key()
        object AddBeat : Key()
        object RemoveBeat : Key()
        object NewStaff : Key()

        /** Takes the whole staff out of the note. */
        object DeleteStaff : Key()
    }

    private const val STEP = 3 // columns from one beat to the next

    /**
     * Applies a pad key with the cursor at [cursor] (which must be in a staff). [cols]: how many
     * characters fit across the screen, so a staff stops growing there and moves on to a new one.
     * Returns the new text and cursor, or null if the cursor isn't in a staff.
     */
    fun key(text: String, cursor: Int, k: Key, cols: Int): Pair<String, Int>? {
        val sp = spot(text, cursor) ?: return null
        val b = sp.block
        val g = Grid.load(text, b)
        var row = sp.row
        var col = sp.col
        val maxWidth = (cols - 2 - g.names.maxOf { it.length }).coerceAtLeast(12)
        val widthBefore = g.width
        when (k) {
            is Key.Digit -> {
                val tok = g.token(row, col)
                val cur = tok?.let { g.rows[row].substring(it.first, it.last + 1) }
                if (k.combine && cur != null && cur.length == 1 && cur[0] in '1'..'2' && (cur + k.d).toInt() <= 24) {
                    col = g.set(row, tok!!.first, cur + k.d)
                } else {
                    col = g.set(row, col, k.d.toString())
                }
            }
            is Key.Mark -> {
                if (k.c == 'x') {
                    col = g.set(row, col, "x")
                } else {
                    val tok = g.token(row, col)
                    val at = if (tok != null) tok.last + 1 else col
                    g.place(row, at, k.c)
                    col = at + 1
                    g.ensure(col + 1)
                }
            }
            Key.Clear -> {
                val tok = g.token(row, col) ?: (if (col > 0) g.token(row, col - 1) else null)
                if (tok != null) {
                    g.clear(row, tok)
                    col = tok.first
                } else {
                    col = (col - 1).coerceAtLeast(0)
                }
            }
            Key.Up -> row = (row - 1).coerceAtLeast(0)
            Key.Down -> row = (row + 1).coerceAtMost(g.rows.size - 1)
            Key.Left -> {
                if (col == 0) return onToPrevStaff(text, b, row) ?: (text to cursor)
                col -= 1
            }
            Key.Right -> {
                // At the end: carry on in the next staff (made if there isn't one).
                if (col + 1 >= g.width) return onToNextStaff(text, b, row, cols)
                col += 1
            }
            Key.AddBeat -> {
                val at = g.token(row, col)?.first ?: col
                g.insertCols(at, STEP)
                col = at
            }
            Key.RemoveBeat -> {
                // The notes on this beat (on any string) plus the gap after them, one beat's worth.
                var a = col
                var z = col
                var any = false
                for (r in g.rows.indices) g.token(r, col)?.let {
                    a = minOf(a, it.first)
                    z = maxOf(z, it.last)
                    any = true
                }
                if (!any) z = col - 1
                while (z - a + 1 < STEP && z + 1 < g.width && g.rows.indices.none { g.isTokAt(it, z + 1) }) z++
                if (z >= a) g.removeCols(a, z - a + 1)
                g.ensure(12)
                col = a.coerceAtMost(g.width - 1)
            }
            Key.NewStaff -> return onToNextStaff(text, b, row, cols, alwaysNew = true)
            Key.DeleteStaff -> {
                var a = b.start
                var z = if (b.end < text.length) b.end + 1 else b.end
                // At the very end: take the line break before it instead.
                if (z == text.length && a > 0 && text[a - 1] == '\n') a--
                // A blank line on both sides: keep just one.
                if (a > 0 && text[a - 1] == '\n' && (a < 2 || text[a - 2] == '\n') && z < text.length && text[z] == '\n') z++
                val t = text.substring(0, a) + text.substring(z)
                return t to a.coerceAtMost(t.length)
            }
        }
        // Never wider than the screen: drop empty columns from the end. A beat that won't fit
        // without pushing notes off the end isn't added.
        if (g.width > maxWidth) g.trimEnd(maxWidth)
        if (k == Key.AddBeat && g.width > maxOf(widthBefore, maxWidth)) return text to cursor
        val newBlockText = g.render()
        val t = text.substring(0, b.start) + newBlockText + text.substring(b.end)
        // Where the cursor lands in the rewritten staff.
        var off = b.start
        for (r in 0 until row) off += g.lineLength(r) + 1
        off += g.names[row].length + 1 + col.coerceIn(0, (g.width - 1).coerceAtLeast(0))
        return t to off
    }

    /** Moves to the last beat of the staff just before [b], or null if there isn't one. */
    private fun onToPrevStaff(text: String, b: Block, row: Int): Pair<String, Int>? {
        val prev = blocks(text).lastOrNull { it.end < b.start } ?: return null
        if (text.substring(prev.end, b.start).isNotBlank()) return null
        val r = row.coerceAtMost(prev.strings - 1)
        val w = Grid.load(text, prev).width
        return text to prev.bodyStarts[r] + (w - 1).coerceAtLeast(0)
    }

    /** Moves to the staff after [b] (making a blank one if there's none right after). */
    private fun onToNextStaff(text: String, b: Block, row: Int, cols: Int, alwaysNew: Boolean = false): Pair<String, Int> {
        if (!alwaysNew) {
            val next = blocks(text).firstOrNull { it.start > b.end }
            // "Right after" = only blank lines between.
            if (next != null && text.substring(b.end, next.start).isBlank()) {
                val r = row.coerceAtMost(next.strings - 1)
                return text to next.bodyStarts[r]
            }
        }
        val (t, c) = insertTemplate(text, b.end, cols)
        val nb = blocks(t).first { c in it }
        return t to nb.bodyStarts[row.coerceAtMost(nb.strings - 1)]
    }

    /** A staff as a grid of characters: every string the same width. */
    private class Grid(val names: List<String>, val rows: List<StringBuilder>, val closing: Boolean) {
        val width: Int get() = rows.maxOf { it.length }

        companion object {
            fun load(text: String, b: Block): Grid {
                val names = ArrayList<String>()
                val bodies = ArrayList<StringBuilder>()
                var closed = 0
                for (i in 0 until b.strings) {
                    names += text.substring(b.lineStarts[i], b.bodyStarts[i] - 1)
                    var body = text.substring(b.bodyStarts[i], b.lineEnds[i]).trimEnd()
                    if (body.endsWith('|')) {
                        body = body.dropLast(1)
                        closed++
                    }
                    bodies += StringBuilder(body.replace(' ', '-'))
                }
                return Grid(names, bodies, closed * 2 >= b.strings).also { it.ensure(it.width) }
            }
        }

        /** Every string at least [w] wide (padded with dashes). */
        fun ensure(w: Int) {
            for (r in rows) while (r.length < w) r.append('-')
        }

        fun render(): String = rows.indices.joinToString("\n") { line(it) }
        fun lineLength(r: Int) = line(r).length
        private fun line(r: Int) = names[r] + "|" + rows[r] + (if (closing) "|" else "")

        fun isTokAt(r: Int, c: Int) = isTok(r, c)
        private fun isTok(r: Int, c: Int) = c in 0 until rows[r].length && rows[r][c] != '-' && rows[r][c] != '|'

        /** The note or mark at [c] on string [r] (a run of digits counts as one), or null. */
        fun token(r: Int, c: Int): IntRange? {
            if (!isTok(r, c)) return null
            val s = rows[r]
            if (!s[c].isDigit()) return c..c
            var a = c
            var z = c
            while (a > 0 && s[a - 1].isDigit()) a--
            while (z + 1 < s.length && s[z + 1].isDigit()) z++
            return a..z
        }

        /** A note or mark starts at [c] on string [r]. */
        fun startsToken(r: Int, c: Int) = token(r, c)?.first == c

        /** Something on another string starts at [c] (so a wider fret here needs room). */
        private fun busyElsewhere(r: Int, c: Int) = rows.indices.any { it != r && startsToken(it, c) }

        fun clear(r: Int, t: IntRange) {
            for (c in t) rows[r].setCharAt(c, '-')
        }

        /** Writes [v] at [c] on string [r] (replacing the note there); returns where it starts. */
        fun set(r: Int, c0: Int, v: String): Int {
            var c = c0
            token(r, c)?.let {
                clear(r, it)
                c = it.first
            }
            // Right after another number on this string: leave a gap so they don't read as one.
            if (c > 0 && v[0].isDigit() && rows[r][c - 1].isDigit()) {
                insertCols(c, 1)
                c++
            }
            for (k in 1..v.length) {
                val q = c + k
                if (q >= width) break
                // Inside the new fret: make room if anything's there. Just after it: keep a gap so
                // two numbers don't run together (a mark like h or / can sit right after).
                val inTheWay = if (k < v.length) isTok(r, q) || busyElsewhere(r, q) else v.last().isDigit() && rows[r][q].isDigit()
                if (inTheWay) insertCols(q, 1)
            }
            ensure(c + v.length + 1)
            for (k in v.indices) rows[r].setCharAt(c + k, v[k])
            return c
        }

        /** Puts a single mark at [c] on string [r], making room if something's already there. */
        fun place(r: Int, c: Int, ch: Char) {
            if (c < width && (isTok(r, c) || busyElsewhere(r, c))) insertCols(c, 1)
            ensure(c + 1)
            rows[r].setCharAt(c, ch)
        }

        /**
         * Adds [n] empty columns at [at] on every string. On a string where a number runs across
         * that spot (the 2 of a 12), they go just after the number instead, so it stays whole;
         * everything after still lines up.
         */
        fun insertCols(at: Int, n: Int) {
            ensure(at)
            for ((i, r) in rows.withIndex()) {
                var p = at
                val t = token(i, at)
                if (t != null && t.first < at) p = t.last + 1
                r.insert(p, "-".repeat(n))
            }
        }

        /** Drops empty columns from the end until it's [w] wide (or only notes are left). */
        fun trimEnd(w: Int) {
            while (width > w && rows.all { it.isNotEmpty() && it[it.length - 1] == '-' }) {
                for (r in rows) r.setLength(r.length - 1)
            }
        }

        fun removeCols(at: Int, n: Int) {
            for (r in rows) if (at < r.length) r.delete(at, minOf(r.length, at + n))
        }
    }
}
