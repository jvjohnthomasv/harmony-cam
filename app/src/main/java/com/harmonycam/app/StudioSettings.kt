package com.harmonycam.app

import android.content.Context

data class StudioSettings(
    val keyRoot: Int = -1,            // -1 = auto-detect
    val minor: Boolean = false,
    val harmonyLevel: Float = 0.8f,
    val leadLevel: Float = 1.0f,
    val humanize: Float = 0.3f,
    val tightness: Float = 0.6f,
    val reverb: Float = 0.35f,
    val width: Float = 0.8f,
    val chordStyle: Boolean = true,        // true: digital chord (reel sound), false: classic intervals
    val handOverlay: Boolean = true,
    val orb: Boolean = true,
    val monitorTuning: Boolean = false,
    val gestures: Boolean = true,
    val swapHands: Boolean = false,
    val holdWhenLost: Boolean = true,
    val showSkeleton: Boolean = true,
    val speakerMonitor: Boolean = false,
    val monitorLevel: Float = 1.0f,
    val inputChannel: Int = 0,        // 0 = interface input 1 (UM2 XLR), 1 = input 2, 2 = mix
    val inputGainDb: Float = 0f,
    val camExtraRotation: Int = 0,
    val camMirror: Boolean = true,
    val manualVoices: Int = 2,
    val manualHeight: Float = 0.5f,
    val manualFx: Float = 0.3f,
    // sound FX
    val effectsOff: Boolean = false,  // plain recording: no harmonies, tuning, reverb, compression…
    val harmoniesOn: Boolean = true,
    val correction: Float = 0f,
    val retune: Float = 0.5f,
    val doubler: Float = 0f,
    val echo: Float = 0f,
    // backing track
    val trackLevel: Float = 0.7f,
    val trackNudgeMs: Int = 0,
    val trackSetsKey: Boolean = true,
    val trackName: String? = null,
) {
    companion object {
        private const val PREFS = "studio"

        fun load(context: Context): StudioSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = StudioSettings()
            return StudioSettings(
                keyRoot = p.getInt("keyRoot", d.keyRoot),
                minor = p.getBoolean("minor", d.minor),
                harmonyLevel = p.getFloat("harmonyLevel", d.harmonyLevel),
                leadLevel = p.getFloat("leadLevel", d.leadLevel),
                humanize = p.getFloat("humanize2", d.humanize),
                tightness = p.getFloat("tightness", d.tightness),
                reverb = p.getFloat("reverb", d.reverb),
                width = p.getFloat("width", d.width),
                chordStyle = p.getBoolean("chordStyle", d.chordStyle),
                handOverlay = p.getBoolean("handOverlay", d.handOverlay),
                orb = p.getBoolean("orb", d.orb),
                monitorTuning = p.getBoolean("monitorTuning", d.monitorTuning),
                gestures = p.getBoolean("gestures", d.gestures),
                swapHands = p.getBoolean("swapHands", d.swapHands),
                holdWhenLost = p.getBoolean("holdWhenLost", d.holdWhenLost),
                showSkeleton = p.getBoolean("showSkeleton", d.showSkeleton),
                speakerMonitor = p.getBoolean("speakerMonitor", d.speakerMonitor),
                monitorLevel = p.getFloat("monitorLevel", d.monitorLevel),
                inputChannel = p.getInt("inputChannel", d.inputChannel),
                inputGainDb = p.getFloat("inputGainDb", d.inputGainDb),
                camExtraRotation = p.getInt("camExtraRotation", d.camExtraRotation),
                camMirror = p.getBoolean("camMirror", d.camMirror),
                manualVoices = p.getInt("manualVoices", d.manualVoices),
                manualHeight = p.getFloat("manualHeight", d.manualHeight),
                manualFx = p.getFloat("manualFx", d.manualFx),
                effectsOff = p.getBoolean("effectsOff", d.effectsOff),
                harmoniesOn = p.getBoolean("harmoniesOn", d.harmoniesOn),
                correction = p.getFloat("correction", d.correction),
                retune = p.getFloat("retune", d.retune),
                doubler = p.getFloat("doubler", d.doubler),
                echo = p.getFloat("echo", d.echo),
                trackLevel = p.getFloat("trackLevel", d.trackLevel),
                trackNudgeMs = p.getInt("trackNudgeMs", d.trackNudgeMs),
                trackSetsKey = p.getBoolean("trackSetsKey", d.trackSetsKey),
                trackName = p.getString("trackName", null),
            )
        }

        fun save(context: Context, s: StudioSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("keyRoot", s.keyRoot).putBoolean("minor", s.minor)
                .putFloat("harmonyLevel", s.harmonyLevel).putFloat("leadLevel", s.leadLevel)
                .putFloat("humanize2", s.humanize).putFloat("tightness", s.tightness)
                .putFloat("reverb", s.reverb).putFloat("width", s.width)
                .putBoolean("chordStyle", s.chordStyle).putBoolean("handOverlay", s.handOverlay)
                .putBoolean("orb", s.orb).putBoolean("monitorTuning", s.monitorTuning)
                .putBoolean("gestures", s.gestures).putBoolean("swapHands", s.swapHands)
                .putBoolean("holdWhenLost", s.holdWhenLost).putBoolean("showSkeleton", s.showSkeleton)
                .putBoolean("speakerMonitor", s.speakerMonitor).putFloat("monitorLevel", s.monitorLevel)
                .putInt("inputChannel", s.inputChannel).putFloat("inputGainDb", s.inputGainDb)
                .putInt("camExtraRotation", s.camExtraRotation).putBoolean("camMirror", s.camMirror)
                .putInt("manualVoices", s.manualVoices).putFloat("manualHeight", s.manualHeight)
                .putFloat("manualFx", s.manualFx)
                .putBoolean("effectsOff", s.effectsOff).putBoolean("harmoniesOn", s.harmoniesOn)
                .putFloat("correction", s.correction).putFloat("retune", s.retune)
                .putFloat("doubler", s.doubler).putFloat("echo", s.echo)
                .putFloat("trackLevel", s.trackLevel).putInt("trackNudgeMs", s.trackNudgeMs)
                .putBoolean("trackSetsKey", s.trackSetsKey).putString("trackName", s.trackName)
                .apply()
        }
    }
}

object Notes {
    private val names = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    fun name(midi: Int): String = if (midi <= 0) "" else names[((midi % 12) + 12) % 12] + (midi / 12 - 1)
    fun pitchClass(pc: Int): String = names[((pc % 12) + 12) % 12]
    fun key(root: Int, minor: Boolean): String = if (root < 0) "Auto" else pitchClass(root) + if (minor) " minor" else " major"
}
