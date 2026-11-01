package com.harmonycam.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Picks the input/output devices: a USB audio interface (e.g. Behringer UM2) wins for both
 * mic and monitoring, then wired headphones, then Bluetooth (monitoring only, it's laggy).
 * Notifies when devices are plugged/unplugged so the engine can re-open on the new route.
 */
class AudioRouting(context: Context, private val onChange: (Route) -> Unit) {

    enum class OutputKind { USB, WIRED, BLUETOOTH, SPEAKER }

    data class Route(
        val inputId: Int,
        val outputId: Int,
        val inputName: String,
        val outputName: String,
        val inputIsUsb: Boolean,
        val inputChannels: Int,
        val output: OutputKind,
    ) {
        /** Safe to monitor without feedback: sound goes to headphones (incl. the interface's jack). */
        val canMonitor get() = output == OutputKind.USB || output == OutputKind.WIRED || output == OutputKind.BLUETOOTH
    }

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    var current: Route = compute()
        private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun register() { am.registerAudioDeviceCallback(callback, handler) }
    fun unregister() { am.unregisterAudioDeviceCallback(callback) }

    private fun refresh() {
        // USB devices enumerate in stages; let them settle before re-routing
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val r = compute()
            if (r != current) { current = r; onChange(r) }
        }, 400)
    }

    private fun compute(): Route {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val usbIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        // Without an interface, use the phone's own mic (better than a headset's in-line mic)
        val builtIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val input = usbIn ?: builtIn

        val usbOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val wiredOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        val btOut = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        val (output, kind) = when {
            usbOut != null -> usbOut to OutputKind.USB
            wiredOut != null -> wiredOut to OutputKind.WIRED
            btOut != null -> btOut to OutputKind.BLUETOOTH
            else -> null to OutputKind.SPEAKER
        }
        val channels = input?.channelCounts?.maxOrNull() ?: 1
        return Route(
            inputId = input?.id ?: 0,
            outputId = output?.id ?: 0,
            inputName = if (usbIn != null) usbIn.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB mic" else "Phone mic",
            outputName = output?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Phone speaker",
            inputIsUsb = usbIn != null,
            inputChannels = if (channels <= 0) 2 else channels,
            output = kind,
        )
    }
}
