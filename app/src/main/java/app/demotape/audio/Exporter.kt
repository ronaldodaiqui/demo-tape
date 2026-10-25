package app.demotape.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.demotape.data.Song
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Mixes a song down to a single file. */
object Exporter {
    enum class Format(val ext: String, val mime: String) {
        WAV("wav", "audio/wav"),
        M4A("m4a", "audio/mp4"),
    }

    fun fileName(song: Song, f: Format): String {
        val clean = song.name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "Demo" }
        return "$clean.${f.ext}"
    }

    fun render(context: Context, song: Song, audio: ClipAudio, f: Format): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, fileName(song, f))
        when (f) {
            Format.WAV -> wav(song, audio, out)
            Format.M4A -> m4a(song, audio, out)
        }
        return out
    }

    private fun toShort(x: Float): Short =
        (softClip(x).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()

    private fun wav(song: Song, audio: ClipAudio, out: File) {
        val len = song.length
        val dataBytes = len * 4 // 16-bit stereo
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { o ->
            fun i32(v: Long) {
                o.write((v and 0xff).toInt()); o.write(((v shr 8) and 0xff).toInt())
                o.write(((v shr 16) and 0xff).toInt()); o.write(((v shr 24) and 0xff).toInt())
            }

            fun i16(v: Int) {
                o.write(v and 0xff); o.write((v shr 8) and 0xff)
            }
            o.write("RIFF".toByteArray()); i32(36 + dataBytes); o.write("WAVE".toByteArray())
            o.write("fmt ".toByteArray()); i32(16); i16(1); i16(2); i32(SR.toLong()); i32(SR * 4L); i16(4); i16(16)
            o.write("data".toByteArray()); i32(dataBytes)

            val src = SongSource(song, audio)
            val bl = FloatArray(BLOCK)
            val br = FloatArray(BLOCK)
            val bytes = ByteArray(BLOCK * 4)
            var p = 0L
            while (p < len) {
                val n = minOf(BLOCK.toLong(), len - p).toInt()
                src.render(p, bl, br, n)
                for (i in 0 until n) {
                    val vl = toShort(bl[i]).toInt()
                    val vr = toShort(br[i]).toInt()
                    bytes[4 * i] = (vl and 0xff).toByte()
                    bytes[4 * i + 1] = ((vl shr 8) and 0xff).toByte()
                    bytes[4 * i + 2] = (vr and 0xff).toByte()
                    bytes[4 * i + 3] = ((vr shr 8) and 0xff).toByte()
                }
                o.write(bytes, 0, n * 4)
                p += n
            }
        }
    }

    private fun m4a(song: Song, audio: ClipAudio, out: File) {
        val len = song.length
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val src = SongSource(song, audio)
        val fl = FloatArray(4096)
        val fr = FloatArray(4096)
        var p = 0L
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear()
                        ib.order(ByteOrder.nativeOrder())
                        val n = minOf(minOf(ib.remaining() / 4, fl.size).toLong(), len - p).toInt()
                        val pts = p * 1_000_000L / SR
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            src.render(p, fl, fr, n)
                            for (i in 0 until n) {
                                ib.putShort(toShort(fl[i]))
                                ib.putShort(toShort(fr[i]))
                            }
                            codec.queueInputBuffer(ii, 0, n * 4, pts, 0)
                            p += n
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIdx = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIdx, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            if (muxing) try { muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }

    /** Copies an export into Downloads/Demo-tape. Android 10 and newer. */
    fun saveToDownloads(context: Context, file: File, f: Format) {
        if (Build.VERSION.SDK_INT < 29) error("Saving needs Android 10 or newer")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, f.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Demo-tape")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the file")
        resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            ?: error("Couldn't write the file")
    }
}
