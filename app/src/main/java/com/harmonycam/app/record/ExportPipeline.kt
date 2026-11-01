package com.harmonycam.app.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.harmonycam.app.audio.NativeEngine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * After a take: studio re-render of the vocal + harmonies (native), AAC 320 kbps encode,
 * mux with the effects video, and save to the gallery (Movies/HarmonyCam).
 */
class ExportPipeline(private val context: Context) {

    class Recording(
        val dryWav: File,
        val automation: File,
        val videoOnly: File?,
        val audioStartNs: Long,
        val videoStartNs: Long,
        val fallbackControls: FloatArray,
        val trackWav: File?,          // backing track that played during the take (null = none)
        val trackStartNs: Long,       // when its first sample reached the headphones
        val trackGain: Float,
        val trackNudgeMs: Int,        // manual sync fine-tune
        val output: File,             // where the finished take goes
    )

    data class Result(val file: File?, val error: String?)

    fun export(take: Recording, progress: (Float, String) -> Unit): Result {
        val work = File(context.cacheDir, "exports").apply { mkdirs() }
        val mixWav = File(work, "mix.wav")
        progress(0f, "Rendering your take")
        val rate = NativeEngine.nSampleRate().toLong()
        val useTrack = take.trackWav != null && take.trackWav.exists() && take.trackStartNs > 0
        // vocal sample index at which the singer heard the track's first sample
        val trackOffset = if (useTrack) (take.trackStartNs - take.audioStartNs) * rate / 1_000_000_000L + take.trackNudgeMs * rate / 1000L else 0L
        val err = NativeEngine.nRender(
            take.dryWav.absolutePath, take.automation.absolutePath, mixWav.absolutePath,
            take.fallbackControls,
            NativeEngine.ProgressListener { p -> progress(p * 0.7f, "Rendering your take"); true },
            if (useTrack) take.trackWav!!.absolutePath else "",
            trackOffset,
            take.trackGain,
        )
        if (err != null) return Result(null, "Render failed: $err")

        progress(0.7f, "Encoding audio")
        val out = take.output
        val audioOffsetUs = (take.audioStartNs - take.videoStartNs) / 1000L
        try {
            val audio = encodeAac(mixWav, audioOffsetUs) { p -> progress(0.7f + 0.2f * p, "Encoding audio") }
            progress(0.9f, "Saving video")
            mux(take.videoOnly, audio, out)
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            out.delete()
            return Result(null, "Export failed: ${e.message}")
        }
        progress(1f, "Done")
        mixWav.delete()
        return Result(out, null)
    }

    private class EncodedAudio(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    /** Reads the float stereo WAV and encodes AAC-LC at 320 kbps. */
    private fun encodeAac(wav: File, offsetUs: Long, progress: (Float) -> Unit): EncodedAudio {
        val raf = RandomAccessFile(wav, "r")
        val (sampleRate, channels, dataOffset, dataBytes) = parseWav(raf)
        val totalFrames = dataBytes / (4L * channels)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 320_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        // skip audio that precedes the first video frame; pad with silence if audio starts later
        var skipFrames = if (offsetUs < 0) (-offsetUs * sampleRate / 1_000_000L) else 0L
        val padFrames = if (offsetUs > 0) (offsetUs * sampleRate / 1_000_000L) else 0L
        skipFrames = skipFrames.coerceAtMost(totalFrames)
        raf.seek(dataOffset + skipFrames * 4L * channels)
        var framesLeft = totalFrames - skipFrames
        var padLeft = padFrames
        var framesQueued = 0L
        val readBuf = ByteArray(1024 * 4 * channels)
        val floatBuf = ByteBuffer.wrap(readBuf).order(ByteOrder.LITTLE_ENDIAN)

        val samples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val totalOut = framesLeft + padLeft
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val capFrames = minOf(1024, inBuf.capacity() / 4) // 16-bit stereo = 4 bytes/frame
                    val ptsUs = framesQueued * 1_000_000L / sampleRate
                    if (padLeft > 0) {
                        val n = minOf(padLeft, capFrames.toLong()).toInt()
                        for (i in 0 until n * 2) inBuf.putShort(0)
                        padLeft -= n; framesQueued += n
                        codec.queueInputBuffer(inIdx, 0, n * 4, ptsUs, 0)
                    } else if (framesLeft > 0) {
                        val n = minOf(framesLeft, capFrames.toLong()).toInt()
                        raf.readFully(readBuf, 0, n * 4 * channels)
                        inBuf.order(ByteOrder.nativeOrder())
                        for (i in 0 until n) {
                            val l = floatBuf.getFloat(i * 4 * channels)
                            val r = if (channels > 1) floatBuf.getFloat(i * 4 * channels + 4) else l
                            inBuf.putShort(toPcm16(l)); inBuf.putShort(toPcm16(r))
                        }
                        framesLeft -= n; framesQueued += n
                        codec.queueInputBuffer(inIdx, 0, n * 4, ptsUs, 0)
                        if (totalOut > 0 && framesQueued % (sampleRate * 2L) < n) progress(framesQueued.toFloat() / totalOut)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.get(bytes)
                        val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        samples.add(bytes to copy)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        }
        val fmt = outFormat ?: codec.outputFormat
        codec.stop(); codec.release(); raf.close()
        return EncodedAudio(fmt, samples)
    }

    private fun toPcm16(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private data class WavInfo(val rate: Int, val channels: Int, val dataOffset: Long, val dataBytes: Long)

    private fun parseWav(raf: RandomAccessFile): WavInfo {
        val hdr = ByteArray(12); raf.readFully(hdr)
        var rate = 48000; var ch = 2
        val chunk = ByteArray(8)
        while (raf.filePointer < raf.length()) {
            raf.readFully(chunk)
            val id = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val fmt = ByteArray(size.toInt()); raf.readFully(fmt)
                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                ch = bb.getShort(2).toInt(); rate = bb.getInt(4)
            } else if (id == "data") {
                return WavInfo(rate, ch, raf.filePointer, minOf(size, raf.length() - raf.filePointer))
            } else raf.seek(raf.filePointer + size + (size and 1))
        }
        throw IllegalStateException("bad wav")
    }

    private fun mux(videoOnly: File?, audio: EncodedAudio, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var extractor: MediaExtractor? = null
        var videoTrack = -1
        if (videoOnly != null && videoOnly.exists() && videoOnly.length() > 0) {
            extractor = MediaExtractor().apply { setDataSource(videoOnly.absolutePath) }
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    extractor.selectTrack(i)
                    videoTrack = muxer.addTrack(f)
                    break
                }
            }
        }
        val audioTrack = muxer.addTrack(audio.format)
        muxer.start()

        val vbuf = ByteBuffer.allocate(4 * 1024 * 1024)
        val vinfo = MediaCodec.BufferInfo()
        var vHas = false
        fun readVideo() {
            val ex = extractor ?: run { vHas = false; return }
            if (videoTrack < 0) { vHas = false; return }
            val size = ex.readSampleData(vbuf, 0)
            if (size < 0) { vHas = false; return }
            val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            vinfo.set(0, size, ex.sampleTime, flags)
            vHas = true
            ex.advance()
        }
        readVideo()
        var ai = 0
        while (vHas || ai < audio.samples.size) {
            val aPts = if (ai < audio.samples.size) audio.samples[ai].second.presentationTimeUs else Long.MAX_VALUE
            if (vHas && vinfo.presentationTimeUs <= aPts) {
                vbuf.position(0); vbuf.limit(vinfo.size)
                muxer.writeSampleData(videoTrack, vbuf, vinfo)
                vbuf.clear()
                readVideo()
            } else {
                val (bytes, info) = audio.samples[ai++]
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(bytes), info)
            }
        }
        muxer.stop(); muxer.release()
        extractor?.release()
    }

    private companion object { const val TAG = "ExportPipeline" }
}
