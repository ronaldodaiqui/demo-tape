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
    /** Audio file id; several loop passes share one recording. Empty means [id]. */
    val file: String = "",
    /** Loop passes recorded in one go share a group; only one of them is shown at a time. */
    val group: String = "",
    val pass: Int = 0,
    val hidden: Boolean = false,
    /** A silent video was filmed with this take (clips/<file>.mp4). */
    val video: Boolean = false,
    /** Video time, in frames, at this take's first sample (before nudging). */
    val videoOffset: Long = 0L,
    /** The video exists but isn't shown on playback. */
    val videoHidden: Boolean = false,
    /** Take volume in dB, -12..24. */
    val gainDb: Float = 0f,
) {
    val gainLin: Float get() = Math.pow(10.0, gainDb / 20.0).toFloat()
    val showsVideo: Boolean get() = video && !videoHidden
    val pos: Long get() = start + nudge
    val end: Long get() = pos + length
    val fileId: String get() = file.ifEmpty { id }
}

data class TrackState(
    val volume: Float = 0.8f,
    val muted: Boolean = false,
    /** Tone in dB, -15..15. */
    val low: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
    /** Center of the mid band in Hz, 200..5000. */
    val midFreq: Float = 1000f,
    /** -1 (left) .. 1 (right). */
    val pan: Float = 0f,
    /** Channel trim in dB, -18..18, on top of the fader. */
    val trim: Float = 0f,
    /** Short label, like "Vocal" or "Gtr 2". */
    val name: String = "",
    val solo: Boolean = false,
) {
    val toneOrPan: Boolean get() = low != 0f || mid != 0f || high != 0f || pan != 0f || trim != 0f
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
    val loopOn: Boolean = false,
    val loopStart: Long = 0L,
    val loopEnd: Long = 0L,
    val drumsOn: Boolean = false,
    val drumBars: Int = 1,
    /** One string per drum voice, 'x' = hit, one character per 16th step. */
    val drumRows: List<String> = emptyList(),
    val drumVolume: Float = 0.8f,
    val drumSwing: Float = 0f,
    /** Lyrics, chords, tuning, reminders. */
    val notes: String = "",
) {
    /** Heard in the mix: not muted, and soloed if any track is soloed. */
    fun audible(t: Int): Boolean {
        val ts = tracks[t]
        return !ts.muted && (ts.solo || tracks.none { it.solo })
    }

    /** The beat grid is on when the click or the drums are. */
    val gridOn: Boolean get() = click || drumsOn

    val hasDrumPattern: Boolean get() = drumRows.any { it.contains('x') }
    val hasLoop: Boolean get() = loopEnd > loopStart
    val length: Long get() = (clips.filter { !it.hidden }.maxOfOrNull { it.end } ?: 0L).coerceAtLeast(0L)
    val visibleClips: List<Clip> get() = clips.filter { !it.hidden }
    fun passes(group: String): List<Clip> =
        if (group.isEmpty()) emptyList() else clips.filter { it.group == group }.sortedBy { it.pass }
    fun hasAudio(track: Int) = clips.any { it.track == track }
}

private val BACKUP_CLIP = Regex("clips/[A-Za-z0-9]+\\.(pcm|peaks2|mp4)")

class SongRepository(private val root: File) {
    init {
        root.mkdirs()
    }

    private fun dir(songId: String) = File(root, songId)
    fun clipFile(songId: String, clipId: String) = File(dir(songId), "clips/$clipId.pcm")
    fun videoFile(songId: String, fileId: String) = File(dir(songId), "clips/$fileId.mp4")
    private fun peaksFile(songId: String, clipId: String) = File(dir(songId), "clips/$clipId.peaks2")

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
        File(dir(songId), "clips/$clipId.peaks").delete()
        videoFile(songId, clipId).delete()
    }

    /**
     * Deletes take files the song no longer uses (takes removed or undone in an earlier
     * session). Files stay around while a song is open so undo and redo can bring them back.
     */
    fun cleanup(song: Song) {
        val keep = song.clips.map { it.fileId }.toSet()
        File(dir(song.id), "clips").listFiles()?.forEach { f ->
            if (f.isFile && f.name.substringBefore('.') !in keep) f.delete()
        }
    }

    fun savePeaks(songId: String, clipId: String, peaks: ByteArray) {
        peaksFile(songId, clipId).writeBytes(peaks)
    }

    /** Writes a whole song (settings, notes, takes, waveforms, video) as one zip. */
    fun exportBackup(songId: String, out: java.io.OutputStream) {
        val d = dir(songId)
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out, 1 shl 16)).use { z ->
            fun put(name: String, f: File) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            put("song.json", File(d, "song.json"))
            File(d, "clips").listFiles()
                ?.filter { it.isFile && BACKUP_CLIP.matches("clips/${it.name}") }
                ?.sortedBy { it.name }
                ?.forEach { put("clips/${it.name}", it) }
        }
    }

    /** Reads a song backup into a new song. Returns null if it isn't a Demo-tape backup. */
    fun importBackup(input: java.io.InputStream): Song? {
        val id = UUID.randomUUID().toString()
        val d = dir(id)
        File(d, "clips").mkdirs()
        var json: String? = null
        try {
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(input, 1 shl 16)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = e.name
                    when {
                        e.isDirectory -> {}
                        n == "song.json" -> json = z.readBytes().toString(Charsets.UTF_8)
                        BACKUP_CLIP.matches(n) -> File(d, n).outputStream().use { z.copyTo(it) }
                    }
                }
            }
            val o = JSONObject(json ?: error("not a backup"))
            o.put("id", id)
            o.put("modified", System.currentTimeMillis())
            val s = fromJson(o)
            save(s)
            return s
        } catch (e: Exception) {
            d.deleteRecursively()
            return null
        }
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
                        .put("midFreq", t.midFreq.toDouble())
                        .put("trim", t.trim.toDouble())
                        .put("name", t.name)
                        .put("solo", t.solo)
                )
            }
        })
        put("bpm", s.bpm)
        put("beatsPerBar", s.beatsPerBar)
        put("click", s.click)
        put("countIn", s.countIn)
        put("clickVolume", s.clickVolume.toDouble())
        put("loopOn", s.loopOn)
        put("loopStart", s.loopStart)
        put("loopEnd", s.loopEnd)
        put("drumsOn", s.drumsOn)
        put("drumBars", s.drumBars)
        put("drumRows", JSONArray(s.drumRows))
        put("drumVolume", s.drumVolume.toDouble())
        put("drumSwing", s.drumSwing.toDouble())
        put("notes", s.notes)
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
                        .put("file", c.file)
                        .put("group", c.group)
                        .put("pass", c.pass)
                        .put("hidden", c.hidden)
                        .put("video", c.video)
                        .put("videoOffset", c.videoOffset)
                        .put("videoHidden", c.videoHidden)
                        .put("gainDb", c.gainDb.toDouble())
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
                midFreq = t.optDouble("midFreq", 1000.0).toFloat(),
                trim = t.optDouble("trim", 0.0).toFloat(),
                name = t.optString("name", ""),
                solo = t.optBoolean("solo", false),
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
                file = c.optString("file", ""),
                group = c.optString("group", ""),
                pass = c.optInt("pass", 0),
                hidden = c.optBoolean("hidden", false),
                video = c.optBoolean("video", false),
                videoOffset = c.optLong("videoOffset", 0L),
                videoHidden = c.optBoolean("videoHidden", false),
                gainDb = c.optDouble("gainDb", 0.0).toFloat(),
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
            loopOn = o.optBoolean("loopOn", false),
            loopStart = o.optLong("loopStart", 0L),
            loopEnd = o.optLong("loopEnd", 0L),
            drumsOn = o.optBoolean("drumsOn", false),
            // Songs saved while drums briefly had four patterns keep pattern A.
            drumBars = (o.optJSONArray("drumPats")?.optJSONObject(0)?.optInt("bars", 1) ?: o.optInt("drumBars", 1)).coerceIn(1, 2),
            drumRows = (o.optJSONArray("drumRows") ?: o.optJSONArray("drumPats")?.optJSONObject(0)?.optJSONArray("rows"))
                ?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            drumVolume = o.optDouble("drumVolume", 0.8).toFloat(),
            drumSwing = o.optDouble("drumSwing", 0.0).toFloat(),
            notes = o.optString("notes", ""),
        )
    }
}
