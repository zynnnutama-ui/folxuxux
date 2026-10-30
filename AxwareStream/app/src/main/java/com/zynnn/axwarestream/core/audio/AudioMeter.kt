package com.zynnn.axwarestream.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Status & level tiap sumber audio, untuk meter di UI (live dan Tes audio). */
data class AudioLevels(
    val running: Boolean = false,
    val micEnabled: Boolean = false,
    val internalEnabled: Boolean = false,
    /** Level RMS 0..1 terbaru. */
    val mic: Float = 0f,
    val internal: Float = 0f,
    /** Puncak tertinggi sejak mulai (0..1) — bukti ada suara yang benar-benar tertangkap. */
    val micMax: Float = 0f,
    val internalMax: Float = 0f,
    val internalError: String? = null,
    val micError: String? = null,
)

object AudioMeter {
    private val _levels = MutableStateFlow(AudioLevels())
    val levels: StateFlow<AudioLevels> = _levels.asStateFlow()

    fun reset(micEnabled: Boolean, internalEnabled: Boolean) {
        _levels.value = AudioLevels(running = true, micEnabled = micEnabled, internalEnabled = internalEnabled)
    }

    fun mic(level: Float, peak: Float) {
        val l = _levels.value
        _levels.value = l.copy(mic = level, micMax = maxOf(l.micMax, peak))
    }

    fun internal(level: Float, peak: Float) {
        val l = _levels.value
        _levels.value = l.copy(internal = level, internalMax = maxOf(l.internalMax, peak))
    }

    fun error(internal: String? = null, mic: String? = null) {
        val l = _levels.value
        _levels.value = l.copy(internalError = internal ?: l.internalError, micError = mic ?: l.micError,
            internalEnabled = if (internal != null) false else l.internalEnabled,
            micEnabled = if (mic != null) false else l.micEnabled)
    }

    fun stopped() { _levels.value = _levels.value.copy(running = false, mic = 0f, internal = 0f) }
}
