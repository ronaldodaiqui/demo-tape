package app.demotape.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val TRACKS = 4

/**
 * One recorded take. Its audio lives in clips/<id>.pcm (16-bit mono, 48 kHz).
 * [start] is the song frame where the take's first sample lands after automatic
 * latency compensation; [nudge] is the manual adjustment on top of that.
 * Later takes cover earlier ones on the same track where they overlap (punch-in).
 * [skip] frames at the start of the file are not used (count-in and pre-roll audio).
 */
data class Clip(
    val id: String,
    val track: Int,
    val start: Long,
    val nudge: Long,
    val length: Long,
    val created: Long,
    val device: String,
    val skip: Long = 0L,
) {
    val pos: Long get() = start + nudge
    val end: Long get() = pos + length
}

data class TrackState(
    val volume: Float = 0.8f,
    val muted: Boolean = false,
    /** Tone in dB, -12..12. */
    val low: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
    /** -1 (left) .. 1 (right). */
    val pan: Float = 0f,
) {
    val toneOrPan: Boolean get() = low != 0f || mid != 0f || high != 0f || pan != 0f
}

data class Song(
    val id: String,
    val name: String,
    val created: Long,
    val modified: Long,
    val tracks: List<TrackState> = List(TRACKS) { TrackState() },
    val clips: List<Clip> = emptyList(),
    val bpm: Int = 100,
    val beatsPerBar: Int = 4,
    val click: Boolean = false,
    val countIn: Boolean = true,
    val clickVolume: Float = 0.7f,
) {
    val length: Long get() = (clips.maxOfOrNull { it.end } ?: 0L).coerceAtLeast(0L)
    fun hasAudio(track: Int) = clips.any { it.track == track }
}

class SongRepository(private val root: File) {
    init {
        root.mkdirs()
    }

    private fun dir(songId: String) = File(root, songId)
    fun clipFile(songId: String, clipId: String) = File(dir(songId), "clips/$clipId.pcm")
    private fun peaksFile(songId: String, clipId: String) = File(dir(songId), "clips/$clipId.peaks")

    fun list(): List<Song> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.modified }

    fun load(id: String): Song? = try {
        fromJson(JSONObject(File(dir(id), "song.json").readText()))
    } catch (e: Exception) {
        null
    }

    fun save(song: Song) {
        val d = dir(song.id)
        File(d, "clips").mkdirs()
        val tmp = File(d, "song.json.tmp")
        tmp.writeText(toJson(song).toString())
        tmp.renameTo(File(d, "song.json"))
    }

    fun create(name: String): Song {
        val now = System.currentTimeMillis()
        val s = Song(UUID.randomUUID().toString(), name, now, now)
        save(s)
        return s
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun deleteClip(songId: String, clipId: String) {
        clipFile(songId, clipId).delete()
        peaksFile(songId, clipId).delete()
    }

    fun savePeaks(songId: String, clipId: String, peaks: ByteArray) {
        peaksFile(songId, clipId).writeBytes(peaks)
    }

    fun loadPeaks(songId: String, clipId: String): ByteArray? =
        peaksFile(songId, clipId).takeIf { it.exists() }?.readBytes()

    private fun toJson(s: Song): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("created", s.created)
        put("modified", s.modified)
        put("tracks", JSONArray().apply {
            s.tracks.forEach { t ->
                put(
                    JSONObject().put("volume", t.volume.toDouble()).put("muted", t.muted)
                        .put("low", t.low.toDouble()).put("mid", t.mid.toDouble())
                        .put("high", t.high.toDouble()).put("pan", t.pan.toDouble())
                )
            }
        })
        put("bpm", s.bpm)
        put("beatsPerBar", s.beatsPerBar)
        put("click", s.click)
        put("countIn", s.countIn)
        put("clickVolume", s.clickVolume.toDouble())
        put("clips", JSONArray().apply {
            s.clips.forEach { c ->
                put(
                    JSONObject()
                        .put("id", c.id)
                        .put("track", c.track)
                        .put("start", c.start)
                        .put("nudge", c.nudge)
                        .put("length", c.length)
                        .put("created", c.created)
                        .put("device", c.device)
                        .put("skip", c.skip)
                )
            }
        })
    }

    private fun fromJson(o: JSONObject): Song {
        val tj = o.optJSONArray("tracks") ?: JSONArray()
        val tracks = List(TRACKS) { i ->
            val t = tj.optJSONObject(i)
            if (t == null) TrackState()
            else TrackState(
                volume = t.optDouble("volume", 0.8).toFloat(),
                muted = t.optBoolean("muted", false),
                low = t.optDouble("low", 0.0).toFloat(),
                mid = t.optDouble("mid", 0.0).toFloat(),
                high = t.optDouble("high", 0.0).toFloat(),
                pan = t.optDouble("pan", 0.0).toFloat(),
            )
        }
        val cj = o.optJSONArray("clips") ?: JSONArray()
        val clips = (0 until cj.length()).map { i ->
            val c = cj.getJSONObject(i)
            Clip(
                id = c.getString("id"),
                track = c.getInt("track"),
                start = c.getLong("start"),
                nudge = c.optLong("nudge", 0L),
                length = c.getLong("length"),
                created = c.optLong("created", 0L),
                device = c.optString("device", ""),
                skip = c.optLong("skip", 0L),
            )
        }
        return Song(
            id = o.getString("id"),
            name = o.optString("name", "Untitled"),
            created = o.optLong("created", 0L),
            modified = o.optLong("modified", 0L),
            tracks = tracks,
            clips = clips,
            bpm = o.optInt("bpm", 100).coerceIn(30, 300),
            beatsPerBar = o.optInt("beatsPerBar", 4).coerceIn(1, 12),
            click = o.optBoolean("click", false),
            countIn = o.optBoolean("countIn", true),
            clickVolume = o.optDouble("clickVolume", 0.7).toFloat(),
        )
    }
}
