package com.harmonycam.app.audio

/** JNI bridge to the C++ harmony engine (see src/main/cpp). */
object NativeEngine {
    init {
        System.loadLibrary("harmonycam")
    }

    // Control indices — must match hc::Controls field order.
    const val C_VOICES = 0
    const val C_HEIGHT = 1
    const val C_MUTE = 2
    const val C_FX = 3
    const val C_KEY_ROOT = 4
    const val C_SCALE = 5
    const val C_HARMONY_LEVEL = 6
    const val C_DRY_LEVEL = 7
    const val C_HUMANIZE = 8
    const val C_TIGHTNESS = 9
    const val C_REVERB = 10
    const val C_WIDTH = 11
    const val NUM_CONTROLS = 12

    // Meter indices — must match hc::MeterIndex.
    const val M_RMS_DB = 0
    const val M_F0 = 1
    const val M_VOICED = 2
    const val M_NOTE = 3
    const val M_KEY_ROOT = 4
    const val M_SCALE = 5
    const val M_VOICE_MIDI0 = 6
    const val M_ENV0 = 10
    const val M_OUT_PEAK = 14
    const val M_XRUNS = 15
    const val M_REC_SECONDS = 16
    const val M_MIDI = 17
    const val M_INPUT_PEAK = 18
    const val M_INPUT_CHANNELS = 19
    const val NUM_METERS = 20

    const val INPUT_CH_FIRST = 0
    const val INPUT_CH_SECOND = 1
    const val INPUT_CH_MIX = 2

    fun interface ProgressListener {
        /** @return false to cancel */
        fun onProgress(progress: Float): Boolean
    }

    external fun nStart(): Boolean
    external fun nStop()
    external fun nIsRunning(): Boolean
    external fun nSampleRate(): Int
    external fun nSetControl(index: Int, value: Float)
    /** Device ids from AudioDeviceInfo.id (0 = system default). Applied on the next nStart(). */
    external fun nSetRouting(inputDeviceId: Int, outputDeviceId: Int, inputChannel: Int)
    external fun nSetInputGainDb(db: Float)
    external fun nSetMonitorGain(gain: Float)
    external fun nSetAsrEnabled(enabled: Boolean)
    external fun nReadAsr(buffer: ShortArray): Int
    external fun nGetMeters(out: FloatArray)
    external fun nStartRecording(wavPath: String, automationPath: String): Boolean

    /** @return [framesWritten, firstFrameMonotonicNanos, sampleRate] */
    external fun nStopRecording(): LongArray

    /** Renders the studio-quality mix. Returns null on success or an error message. */
    external fun nRender(
        dryWav: String,
        automation: String,
        outWav: String,
        fallbackControls: FloatArray,
        listener: ProgressListener?
    ): String?
}
