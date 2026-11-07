package app.demotape.ui

import android.content.Context
import java.io.BufferedReader
import kotlin.random.Random

/**
 * Nonsense lyric lines, one at a time, for the notes page.
 *
 * Source, shipped in assets/lyric_lines.txt:
 *  - about 150,000 lines of poetry (Project Gutenberg, public domain), mostly from 1875 on.
 *    Old-timey words are swapped for modern ones (thee → you, steed → horse, beheld → saw,
 *    morn → morning…), and lines with old-timey words that have no good swap (forthwith,
 *    durst, ev'ry…) are left out. Unusual but timeless words (gleam, gossamer, starry) stay;
 *  - a sprinkle of everyday old prose (cookbooks, etiquette and letter-writing guides, field
 *    guides to birds, wild flowers, mushrooms and insects, weather proverbs), treated the same;
 *  - modern everyday sentences from Mozilla Common Voice (CC0), so it doesn't all sound old.
 * Mixed together they give lines like "comes mr groundhog to assist".
 *
 * A line is grown backwards from its last word, Markov-style: from each word it jumps to a word
 * that came before it somewhere in the poems (sometimes the two words before, which keeps
 * it half-grammatical; sometimes just one, which lets it drift into nonsense). Building from
 * the end means the ending can be chosen first:
 *
 *  - Slant rhymes, not perfect ones: the ending shares the vowel sounds of the word it answers
 *    from the stressed syllable on (home → alone, road, cold; river → silver, mirror) but not
 *    the consonants, so it never lands on a corny exact rhyme (home → roam).
 *    Pronunciations come from the CMU Pronouncing Dictionary (public domain).
 *  - Lines rhyme every other line within a verse (ABAB); a blank line starts a new verse.
 *  - Length: it aims for about as many syllables as the line above, so lines sing alike.
 */
object LyricLines {
    private const val MAX_WORDS = 18
    private val VOWELS = setOf("AA", "AE", "AH", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW", "@")

    /** How a word sounds: syllable count, and its sounds from the stressed vowel on. */
    class Sound(val syllables: Int, val tail: List<String>) {
        val vowels: List<String> = tail.filter { it in VOWELS }
    }

    class Model internal constructor(
        private val toks: IntArray, // all lines, each reversed, separated by 0
        private val words: Array<String>,
        private val pos: Array<IntArray>, // where each word id occurs in toks
        private val ends: IntArray, // word ids that end some line
        private val seen: IntArray, // sorted hashes of the original lines (so we never just copy one)
        private val sounds: Map<String, Sound>,
        private val moreSounds: (String) -> Sound?,
    ) {
        private val extra = HashMap<String, Sound?>()

        fun sound(w: String): Sound? {
            val k = w.lowercase().trim('\'')
            sounds[k]?.let { return it }
            return synchronized(extra) { extra.getOrPut(k) { moreSounds(k) } }
        }

        fun syllables(ws: List<String>): Int = ws.sumOf { w -> sound(w)?.syllables ?: guessSyllables(w) }

        /** Words that end a line and slant-rhyme with [w], or null if there's no telling. */
        internal fun partners(w: String): IntArray? {
            val s = sound(w) ?: return null
            val key = w.lowercase().trim('\'')
            fun related(o: String) = o.startsWith(key) || key.startsWith(o)
            // A tail that's the same plus an s (down / crowns) is really the same rhyme.
            fun same(t: List<String>) = t == s.tail ||
                (t.size == s.tail.size + 1 && t.last() in setOf("S", "Z") && t.dropLast(1) == s.tail) ||
                (s.tail.size == t.size + 1 && s.tail.last() in setOf("S", "Z") && s.tail.dropLast(1) == t)
            val slant = ends.filter { e ->
                val o = words[e]
                val so = sounds[o] ?: return@filter false
                so.vowels == s.vowels && !same(so.tail) && !related(o)
            }
            if (slant.size >= 4) return slant.toIntArray()
            // Few of those: just the stressed vowel.
            val loose = ends.filter { e ->
                val o = words[e]
                val so = sounds[o] ?: return@filter false
                so.tail.first() == s.tail.first() && !same(so.tail) && !related(o)
            }
            return if (loose.isEmpty()) null else loose.toIntArray()
        }

        /**
         * A new line. [rhymeWith]: the word to slant-rhyme with (null for none);
         * [syllables]: about how long (null for anything); [spread]: how far the length may
         * wander from that, so lines aren't all the same size.
         *
         * Each roll has its own mood, so lines vary: a splice (the start of one line glued to
         * the end of another where they share a word, magnetic-poetry style), or the
         * word-by-word chain kept close to real phrases, middling, or wild.
         */
        fun line(rhymeWith: String?, syllables: Int?, r: Random = Random, spread: Int = 0): String {
            val target = when {
                syllables != null -> (syllables + if (spread > 0) r.nextInt(-spread, spread + 1) else 0).coerceIn(3, 22)
                else -> when (r.nextInt(20)) {
                    in 0..5 -> 4 + r.nextInt(3) // short
                    in 6..14 -> 7 + r.nextInt(4) // medium
                    else -> 11 + r.nextInt(6) // long
                }
            }
            val pool = rhymeWith?.let { partners(it) } ?: ends
            val mood = r.nextInt(4)
            fun make(m: Int): List<String> {
                val end = pool[r.nextInt(pool.size)]
                return when (m) {
                    0 -> splice(end, r) ?: grow(end, target, r, 0.5f)
                    1 -> grow(end, target, r, 0.85f)
                    2 -> grow(end, target, r, 0.5f)
                    else -> grow(end, target, r, 0.2f)
                }
            }
            val got = ArrayList<Pair<Int, List<String>>>()
            repeat(40) {
                val ws = make(mood)
                if (ws.size >= 3 && seen.binarySearch(ws.joinToString(" ").hashCode()) < 0) {
                    got += kotlin.math.abs(syllables(ws) - target) to ws
                }
            }
            if (got.isEmpty()) return tidy(make(2))
            got.sortBy { it.first }
            val close = got.filter { it.first <= 1 }.ifEmpty { got.take(3) }
            return tidy(close[r.nextInt(close.size)].second)
        }

        /** Word-by-word, backwards from [end]; [stick]: how often it keeps two words of context. */
        private fun grow(end: Int, target: Int, r: Random, stick: Float): List<String> {
            val out = ArrayList<Int>()
            out += end
            while (out.size < MAX_WORDS) {
                val at = pos[out.last()]
                // A spot where the word was; sometimes one where the word after it matches too.
                var q = at[r.nextInt(at.size)]
                if (out.size >= 2 && r.nextFloat() < stick) {
                    val prev = out[out.size - 2]
                    find(at, r) { toks[it - 1] == prev }?.let { q = it }
                }
                var next = toks[q + 1]
                if (next == 0) {
                    if (syllables(out.map { words[it] }) >= target - 1) break
                    next = find(at, r) { toks[it + 1] != 0 }?.let { toks[it + 1] } ?: break
                }
                out += next
            }
            return out.reversed().map { words[it] }
        }

        /**
         * The end of a real line that ends in [end], glued onto the start of another line at a
         * word they share. Null if there's nothing to glue.
         */
        private fun splice(end: Int, r: Random): List<String>? {
            val p = find(pos[end], r) { toks[it - 1] == 0 } ?: return null
            var len = 0
            while (toks[p + len] != 0) len++
            if (len < 2) return null
            val keep = 1 + r.nextInt(len - 1) // words kept from this line, from its end
            val out = ArrayList<Int>()
            for (k in 0 until keep) out += toks[p + k]
            val pivot = out.last()
            // The same word somewhere else (not in this line), then on to that line's start.
            val q = find(pos[pivot], r) { it < p || it >= p + len } ?: return null
            var j = q + 1
            while (toks[j] != 0 && out.size < MAX_WORDS) out += toks[j++]
            return out.reversed().map { words[it] }
        }

        /** The first entry of [a] (going round from a random start) that passes [ok]. */
        private inline fun find(a: IntArray, r: Random, ok: (Int) -> Boolean): Int? {
            val start = r.nextInt(a.size)
            for (k in a.indices) {
                val v = a[(start + k) % a.size]
                if (ok(v)) return v
            }
            return null
        }
    }

    @Volatile
    private var model: Model? = null

    fun model(ctx: Context): Model = model ?: synchronized(this) {
        model ?: build(
            ctx.assets.open("lyric_lines.txt").bufferedReader().useLines { build(it) },
            ctx.assets.open("sounds.txt").bufferedReader().readText(),
        ) { w -> ctx.assets.open("sounds_more.txt").bufferedReader().use { findSound(it, w) } }.also { model = it }
    }

    /** The lines, read once into compact arrays. */
    class Corpus internal constructor(
        val toks: IntArray,
        val words: Array<String>,
        val ends: IntArray,
        val seen: IntArray,
    )

    fun build(lines: Sequence<String>): Corpus {
        val ids = HashMap<String, Int>()
        val words = ArrayList<String>().apply { add("") }
        var toks = IntArray(1 shl 20)
        var n = 0
        fun push(x: Int) {
            if (n == toks.size) toks = toks.copyOf(n * 2)
            toks[n++] = x
        }
        var seen = IntArray(1 shl 16)
        var ns = 0
        val endSet = LinkedHashSet<Int>()
        push(0)
        for (l in lines) {
            val ws = l.trim().split(' ').filter { it.isNotEmpty() }
            if (ws.isEmpty()) continue
            if (ns == seen.size) seen = seen.copyOf(ns * 2)
            seen[ns++] = ws.joinToString(" ").hashCode()
            for ((i, w) in ws.asReversed().withIndex()) {
                val id = ids.getOrPut(w) { words.add(w); words.size - 1 }
                if (i == 0) endSet += id
                push(id)
            }
            push(0)
        }
        val s = seen.copyOf(ns).also { it.sort() }
        return Corpus(toks.copyOf(n), words.toTypedArray(), endSet.toIntArray(), s)
    }

    fun build(c: Corpus, sounds: String, more: (String) -> Sound?): Model {
        val t = c.toks
        val counts = IntArray(c.words.size)
        for (x in t) if (x != 0) counts[x]++
        val pos = Array(c.words.size) { IntArray(counts[it]) }
        val fill = IntArray(c.words.size)
        for ((i, x) in t.withIndex()) if (x != 0) pos[x][fill[x]++] = i
        val snd = HashMap<String, Sound>()
        for (l in sounds.lineSequence()) parseSound(l)?.let { snd[it.first] = it.second }
        val ends = c.ends.filter { c.words[it] in snd }.toIntArray()
        return Model(t, c.words, pos, ends, c.seen, snd, more)
    }

    private fun parseSound(l: String): Pair<String, Sound>? {
        val p = l.trim().split(' ')
        if (p.size != 3) return null
        return p[0] to Sound(p[1].toIntOrNull() ?: return null, p[2].split('.'))
    }

    /** Looks one word up in a sounds file (sorted, one word per line). */
    fun findSound(r: BufferedReader, w: String): Sound? {
        val prefix = "$w "
        while (true) {
            val l = r.readLine() ?: return null
            if (l.startsWith(prefix)) return parseSound(l)?.second
        }
    }

    private fun guessSyllables(w: String): Int =
        Regex("[aeiouy]+").findAll(w.lowercase().trimEnd('e')).count().coerceAtLeast(1)

    private fun tidy(ws: List<String>): String {
        val s = ws.joinToString(" ") { w ->
            if (w == "i" || w.startsWith("i'")) "I" + w.drop(1) else w
        }
        return s.replaceFirstChar { it.uppercaseChar() }
    }

    private val CHORD = Regex("^[A-G][#b♯♭]?(m|maj|min|dim|aug|sus|add)?[0-9]*(/[A-G][#b♯♭]?)?$")

    /** A lyric line (not blank, not a row of chords). */
    private fun isLyric(l: String): Boolean {
        val ws = l.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return ws.isNotEmpty() && !ws.all { CHORD.matches(it) }
    }

    /**
     * What the next line should do, given the note's lines before where it goes: the word to
     * rhyme with (every other line within a verse), and the length to aim for (the line above).
     * A blank line just above starts a new verse, so the line is free.
     */
    fun context(m: Model, before: List<String>): Pair<String?, Int?> {
        val verse = ArrayList<String>() // newest first
        for (l in before.asReversed()) {
            if (l.isBlank()) break
            if (isLyric(l)) verse += l
        }
        val len = verse.firstOrNull()?.let { m.syllables(wordsOf(it)) }
        val rhyme = verse.getOrNull(1)?.let { wordsOf(it).lastOrNull() }
        return rhyme to len
    }

    private fun wordsOf(l: String) =
        Regex("[A-Za-z']+").findAll(l).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()

    /**
     * Where a line goes in [text] with the cursor at [cursor]: on the cursor's line if it's
     * empty, otherwise on a new line just below it. Returns the lines before that spot.
     */
    fun linesBefore(text: String, cursor: Int): List<String> {
        val c = cursor.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', c - 1) + 1
        val end = text.indexOf('\n', c).let { if (it < 0) text.length else it }
        val upTo = if (text.substring(start, end).isBlank()) start else end
        if (upTo == 0) return emptyList()
        val head = text.substring(0, upTo)
        return head.removeSuffix("\n").split('\n')
    }

    /** Puts [line] in [text] (see [linesBefore]); returns the new text and cursor (end of the line). */
    fun insert(text: String, cursor: Int, line: String): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', c - 1) + 1
        val end = text.indexOf('\n', c).let { if (it < 0) text.length else it }
        return if (text.substring(start, end).isBlank()) {
            (text.substring(0, start) + line + text.substring(end)) to start + line.length
        } else {
            (text.substring(0, end) + "\n" + line + text.substring(end)) to end + 1 + line.length
        }
    }
}
