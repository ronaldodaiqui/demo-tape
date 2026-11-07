package app.demotape.ui

import android.content.Context
import kotlin.random.Random

/**
 * Random song titles, cut from old public-domain books and poems (Blake, Whitman, Carroll,
 * Melville, Chesterton, Shakespeare and a few more), shipped as short phrases in
 * assets/title_words.txt. Each roll uses one of two techniques:
 *
 *  - Markov chain: learns which words follow which in those phrases, then wanders off from a
 *    random spot, giving half-grammatical, dreamlike lines.
 *  - Cut-up (Bowie's Verbasizer, Burroughs before him): snips two or three fragments from
 *    different phrases and glues them together.
 *
 * Titles don't have to make sense; that's the point.
 */
object SongNames {
    class Model internal constructor(
        val lines: List<List<String>>,
        val next2: Map<String, List<String>>,
        val next1: Map<String, List<String>>,
    )

    @Volatile
    private var model: Model? = null

    private val STOP = (
        "a an the of in on and to for at by with from or but as is was are be it its that this i my me you your " +
            "he she his her we our they them their so if then than not no all into upon there here when what who " +
            "which whose up out down off over under"
        ).split(" ").toSet()
    private val SMALL = "a an the of in on and to for at by with from or but as".split(" ").toSet()
    private val LEAD = "and but or so then yet that which who is was are were be been has had have do did does to as than if"
        .split(" ").toSet()
    private val GLUE = LEAD + "the a an of in on with by for at from into upon my his her our their its".split(" ")

    fun fromText(text: String): Model {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { it.split(' ') }.toList()
        val n2 = HashMap<String, MutableList<String>>()
        val n1 = HashMap<String, MutableList<String>>()
        for (l in lines) {
            val seq = listOf("^") + l + "$"
            for (i in 0 until seq.size - 1) {
                n1.getOrPut(seq[i]) { ArrayList() }.add(seq[i + 1])
                if (i + 2 < seq.size) n2.getOrPut(seq[i] + " " + seq[i + 1]) { ArrayList() }.add(seq[i + 2])
            }
        }
        return Model(lines, n2, n1)
    }

    private fun model(ctx: Context): Model = model ?: synchronized(this) {
        model ?: fromText(ctx.assets.open("title_words.txt").bufferedReader().readText()).also { model = it }
    }

    fun random(ctx: Context, r: Random = Random): String = random(model(ctx), r)

    fun random(m: Model, r: Random = Random): String {
        repeat(60) {
            val w = if (r.nextBoolean()) markov(m, r) else cutUp(m, r)
            if (w != null) return title(w)
        }
        return "Untitled"
    }

    private fun <T> List<T>.pick(r: Random) = this[r.nextInt(size)]

    private fun markov(m: Model, r: Random): List<String>? {
        val start = m.lines.pick(r)
        val i = r.nextInt(start.size)
        val w = mutableListOf(start[i])
        var prev = (if (i == 0) "^" else start[i - 1]) to start[i]
        val n = 2 + r.nextInt(5)
        while (w.size < n) {
            val opts = (if (r.nextFloat() < 0.75f) m.next2["${prev.first} ${prev.second}"] else null) ?: m.next1[prev.second]
                ?: break
            val c = opts.pick(r)
            if (c == "$") break
            w += c
            prev = prev.second to c
        }
        return tidy(w).takeIf(::good)
    }

    private fun cutUp(m: Model, r: Random): List<String>? {
        val parts = ArrayList<String>()
        repeat(if (r.nextInt(3) == 0) 3 else 2) {
            val l = m.lines.pick(r)
            val n = 1 + r.nextInt(minOf(3, l.size))
            val i = r.nextInt(l.size - n + 1)
            var frag = l.subList(i, i + n)
            while (frag.isNotEmpty() && frag[0] in (if (parts.isEmpty()) LEAD else GLUE)) frag = frag.drop(1)
            while (frag.isNotEmpty() && frag.last() in STOP) frag = frag.dropLast(1)
            parts += frag
        }
        return tidy(parts).takeIf(::good)
    }

    private fun tidy(w0: List<String>): List<String> {
        var w = w0
        while (w.isNotEmpty() && w.first() in LEAD) w = w.drop(1)
        while (w.isNotEmpty() && w.last() in STOP) w = w.dropLast(1)
        return w
    }

    /** Two to five words, not too long, with something to chew on. */
    private fun good(w: List<String>): Boolean {
        val content = w.filter { it !in STOP && it.length >= 4 }
        return w.size in 2..5 && w.joinToString(" ").length <= 34 &&
            (content.size >= 2 || content.any { it.length >= 7 })
    }

    private fun title(w: List<String>) = w.mapIndexed { i, x ->
        when {
            x == "i" || x.startsWith("i'") -> x.replaceFirstChar { it.uppercase() }
            i > 0 && x in SMALL -> x
            else -> x.replaceFirstChar { it.uppercase() }
        }
    }.joinToString(" ")
}
