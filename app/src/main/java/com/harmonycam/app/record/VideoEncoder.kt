package com.harmonycam.app.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * H.264 encoder fed by an input Surface (the GL renderer draws the effects layer into it).
 * Writes a video-only MP4; audio is added after the studio re-render.
 */
class VideoEncoder private constructor(
    private val codec: MediaCodec,
    val inputSurface: Surface,
    val width: Int,
    val height: Int,
    private val outFile: File,
) {
    private val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    private val drainThread: Thread
    @Volatile private var finished = false
    @Volatile var framesWritten = 0
        private set

    init {
        codec.start()
        drainThread = Thread({ drainLoop() }, "VideoEncoderDrain").apply { start() }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) { break }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (finished && eosTimeout()) break
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && muxerStarted &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                        framesWritten++
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private var eosWaitStart = 0L
    private fun eosTimeout(): Boolean {
        if (eosWaitStart == 0L) eosWaitStart = System.currentTimeMillis()
        return System.currentTimeMillis() - eosWaitStart > 3000
    }

    /** Call after the GL side has stopped drawing into [inputSurface]. Returns the MP4 file. */
    fun finish(): File? {
        finished = true
        try { codec.signalEndOfInputStream() } catch (e: Exception) { Log.w(TAG, "EOS", e) }
        drainThread.join(5000)
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        inputSurface.release()
        return try {
            if (muxerStarted) { muxer.stop(); muxer.release(); outFile } else { muxer.release(); null }
        } catch (e: Exception) {
            Log.e(TAG, "muxer stop failed", e); null
        }
    }

    companion object {
        private const val TAG = "VideoEncoder"

        /** Tries 1080×1920 @ 14 Mbps first, falls back to 720×1280 on weaker encoders. */
        fun create(outFile: File): VideoEncoder? {
            val attempts = listOf(Triple(1080, 1920, 14_000_000), Triple(720, 1280, 8_000_000))
            for ((w, h, bitrate) in attempts) {
                var codec: MediaCodec? = null
                try {
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                    }
                    codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    try {
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    } catch (e: Exception) {
                        // some encoders reject explicit profile/level: retry with defaults
                        format.removeKey(MediaFormat.KEY_PROFILE)
                        format.removeKey(MediaFormat.KEY_LEVEL)
                        codec.reset()
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    }
                    val surface = codec.createInputSurface()
                    return VideoEncoder(codec, surface, w, h, outFile)
                } catch (e: Exception) {
                    Log.w(TAG, "encoder ${w}x$h failed", e)
                    try { codec?.release() } catch (_: Exception) {}
                }
            }
            return null
        }
    }
}
