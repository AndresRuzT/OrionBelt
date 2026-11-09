package com.audacia.orion.feedback

import android.content.Context
import com.audacia.orion.net.BuzzerClient
import com.audacia.orion.net.XiaoEndpoint
import com.audacia.orion.vision.ZoneResult

data class FeedbackSettings(
    val hapticEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    val buzzerEnabled: Boolean = true,
    val voiceEnabled: Boolean = true
)

/**
 * Coordinador central de todos los canales de retroalimentación sensorial de ORION v6:
 * - Háptica direccional en el teléfono (vibración).
 * - Audio estéreo binaural en el teléfono (auriculares/altavoz).
 * - Buzzer remoto en el hardware del cinturón (XIAO ESP32S3).
 * - Avisos de voz TTS para estados del sistema.
 */
class FeedbackCoordinator(
    context: Context,
    val alertPolicy: AlertPolicy = AlertPolicy(),
    val haptic: HapticFeedback = HapticFeedback(context),
    val audio: AudioFeedback = AudioFeedback(context),
    val buzzer: BuzzerClient = BuzzerClient(),
    val voice: VoiceAnnouncer = VoiceAnnouncer(context)
) : AutoCloseable {

    var settings: FeedbackSettings = FeedbackSettings()
        set(value) {
            field = value
            haptic.isEnabled = value.hapticEnabled
            audio.isEnabled = value.audioEnabled
            voice.isEnabled = value.voiceEnabled
        }

    init {
        haptic.isEnabled = settings.hapticEnabled
        audio.isEnabled = settings.audioEnabled
        voice.isEnabled = settings.voiceEnabled
    }

    fun processFrameResult(
        result: ZoneResult,
        endpoint: XiaoEndpoint?,
        nowMs: Long = System.currentTimeMillis()
    ): Alert? {
        val alert = alertPolicy.update(result, nowMs)
        if (alert != null) {
            // 1. Vibración háptica en el celular
            haptic.play(alert)

            // 2. Audio estéreo direccional en el celular
            audio.play(alert)

            // 3. Buzzer físico en el cinturón (XIAO ESP32S3)
            if (settings.buzzerEnabled && endpoint != null) {
                val (freq, duration) = when (alert.level) {
                    3 -> Pair(2400, 100)
                    2 -> Pair(1900, 80)
                    1 -> Pair(1400, 60)
                    else -> Pair(900, 45)
                }
                buzzer.triggerBeep(endpoint, durationMs = duration, frequencyHz = freq)
            }
        }
        return alert
    }

    fun onCameraConnected() {
        if (settings.voiceEnabled) {
            voice.announceCameraConnected()
        }
    }

    fun onCameraDisconnected() {
        if (settings.voiceEnabled) {
            voice.announceCameraDisconnected()
        }
    }

    fun onOrionReady() {
        if (settings.voiceEnabled) {
            voice.announceReady()
        }
    }

    override fun close() {
        haptic.cancel()
        audio.close()
        voice.close()
    }
}
