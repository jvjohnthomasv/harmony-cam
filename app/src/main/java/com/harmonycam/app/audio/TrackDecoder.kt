package com.harmonycam.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes an MP3 / AAC / M4A / WAV / FLAC / OGG backing track into a 16-bit PCM WAV file. */
object TrackDecoder {

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n.substringBeforeLast('.')
            }
        }
        return "Backing track"
    }

    fun decode(context: Context, uri: Uri, out: File, progress: (Float) -> Unit): Boolean {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIndex = i; format = f; break }
        }
        if (trackIndex < 0 || format == null) { extractor.release(); return false }
        extractor.selectTrack(trackIndex)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        out.parentFile?.mkdirs()
        val raf = RandomAccessFile(out, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44)) // header placeholder
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        var dataBytes = 0L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var chunk = ByteArray(0)
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) progress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (pcmFloat) {
                                // convert float → 16-bit
                                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                val n = fb.remaining()
                                if (chunk.size < n * 2) chunk = ByteArray(n * 2)
                                val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                                for (i in 0 until n) bb.putShort((fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                                raf.write(chunk, 0, n * 2)
                                dataBytes += n * 2L
                            } else {
                                if (chunk.size < info.size) chunk = ByteArray(info.size)
                                buf.get(chunk, 0, info.size)
                                raf.write(chunk, 0, info.size)
                                dataBytes += info.size
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
        // WAV header (16-bit PCM)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
        h.putInt(sampleRate); h.putInt(sampleRate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
        progress(1f)
        return dataBytes > 0
    }
}
