package app.demotape.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A drum pattern saved for use in any song. Rows are 16th steps; [stepsPerBar] is what it was made in. */
data class SavedPattern(
    val name: String,
    val rows: List<String>,
    val bars: Int,
    val stepsPerBar: Int,
    val swing: Float,
)

/** The user's saved drum patterns, shared across all songs. */
class PatternLibrary(context: Context) {
    private val file = File(context.filesDir, "drum_patterns.json")
    val patterns = MutableStateFlow(load())

    private fun load(): List<SavedPattern> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val r = o.getJSONArray("rows")
            SavedPattern(
                name = o.getString("name"),
                rows = (0 until r.length()).map { r.getString(it) },
                bars = o.optInt("bars", 1).coerceIn(1, 4),
                stepsPerBar = o.optInt("stepsPerBar", 16),
                swing = o.optDouble("swing", 0.0).toFloat(),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun store(list: List<SavedPattern>) {
        patterns.value = list
        val a = JSONArray()
        list.forEach { p ->
            a.put(
                JSONObject()
                    .put("name", p.name)
                    .put("rows", JSONArray(p.rows))
                    .put("bars", p.bars)
                    .put("stepsPerBar", p.stepsPerBar)
                    .put("swing", p.swing.toDouble())
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    /** Saves (or replaces, by name) a pattern. */
    fun save(p: SavedPattern) = store(patterns.value.filter { it.name != p.name } + p)

    fun delete(name: String) = store(patterns.value.filter { it.name != name })
}
