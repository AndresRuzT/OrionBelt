package com.audacia.orion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audacia.orion.feedback.FeedbackSettings
import com.audacia.orion.vision.InferenceBackend
import com.audacia.orion.vision.VisionPipeline
import com.audacia.orion.vision.VisionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HudViewModel(application: Application) : AndroidViewModel(application) {

    private val pipeline = VisionPipeline(
        context = application.applicationContext,
        scope = viewModelScope
    )

    val state: StateFlow<VisionState> = pipeline.state

    init {
        // Búsqueda y autoconexión automática activa por defecto al abrir la app
        startAutoDiscovery()
    }

    fun startAutoDiscovery() {
        pipeline.startAutoDiscovery()
    }

    fun connectDirect(ip: String) {
        pipeline.connectDirect(ip)
    }

    fun startDemo() {
        pipeline.startDemoMode()
    }

    fun stop() {
        pipeline.stop()
    }

    fun switchBackend(backend: InferenceBackend) {
        pipeline.switchBackend(backend)
    }

    fun updateFeedbackSettings(settings: FeedbackSettings) {
        pipeline.updateFeedbackSettings(settings)
    }

    fun runBenchmark(onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val result = pipeline.runDeviceBenchmark(50)
            onDone(result)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pipeline.close()
    }
}
