package com.audacia.orion.vision

import android.graphics.Bitmap
import com.audacia.orion.feedback.Alert
import com.audacia.orion.feedback.FeedbackSettings
import com.audacia.orion.net.XiaoEndpoint
import com.audacia.orion.resilience.ThermalThrottleLevel
import com.audacia.orion.resilience.WatchdogState

enum class ConnectionStatus {
    IDLE,
    SEARCHING,
    CONNECTING,
    STREAMING,
    DEMO_MODE,
    ERROR
}

data class VisionState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val endpoint: XiaoEndpoint? = null,
    val cameraBitmap: Bitmap? = null,
    val depthBitmap: Bitmap? = null,
    val zoneResult: ZoneResult? = null,
    val inferenceMs: Long = 0,
    val totalLatencyMs: Long = 0,
    val fps: Float = 0.0f,
    val activeBackend: InferenceBackend = InferenceBackend.CPU,
    val framesProcessed: Long = 0,
    val activeAlert: Alert? = null,
    val feedbackSettings: FeedbackSettings = FeedbackSettings(),
    val statusMessage: String = "Listo para iniciar",
    val benchmarkSummary: String? = null,
    val watchdogState: WatchdogState = WatchdogState.OK,
    val thermalLevel: ThermalThrottleLevel = ThermalThrottleLevel.NOMINAL
)
