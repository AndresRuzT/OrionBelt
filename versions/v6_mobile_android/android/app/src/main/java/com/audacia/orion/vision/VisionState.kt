package com.audacia.orion.vision

import android.graphics.Bitmap
import com.audacia.orion.net.XiaoEndpoint

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
    val statusMessage: String = "Listo para iniciar",
    val benchmarkSummary: String? = null
)
