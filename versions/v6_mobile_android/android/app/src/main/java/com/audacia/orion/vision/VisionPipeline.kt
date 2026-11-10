package com.audacia.orion.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import com.audacia.orion.feedback.FeedbackCoordinator
import com.audacia.orion.feedback.FeedbackSettings
import com.audacia.orion.net.MjpegStreamReader
import com.audacia.orion.net.XiaoDiscovery
import com.audacia.orion.net.XiaoEndpoint
import com.audacia.orion.resilience.CameraWatchdog
import com.audacia.orion.resilience.ThermalGovernor
import com.audacia.orion.resilience.WatchdogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Pipeline integral de visión en tiempo real para ORION v6.
 *
 * Conecta:
 * 1. Autodescubrimiento UDP del XIAO ESP32S3.
 * 2. Consumo de stream MJPEG sobre HTTP.
 * 3. Buffer de descarte dinámico (Channel.CONFLATED) para latencia cero.
 * 4. Preprocesamiento ImageNet bilineal.
 * 5. Inferencia TFLite acelerada por GPU móvil o CPU multi-hilo.
 * 6. Normalización, segmentación por zonas y generación de mapa Magma.
 */
class VisionPipeline(
    private val context: Context,
    private val scope: CoroutineScope
) : AutoCloseable {

    private val _state = MutableStateFlow(VisionState())
    val state: StateFlow<VisionState> = _state.asStateFlow()

    private var preferredBackend = InferenceBackend.GPU
    private var depthEstimator: DepthEstimator? = null
    private val preprocessor = Preprocessor()
    private val discovery = XiaoDiscovery(context)
    private val streamReader = MjpegStreamReader()
    val feedback = FeedbackCoordinator(context)
    val watchdog = CameraWatchdog(timeoutMs = 3500L)
    val thermalGovernor = ThermalGovernor(context, feedback.voice) { level ->
        _state.update { it.copy(thermalLevel = level) }
    }

    private var pipelineJob: Job? = null
    private var demoJob: Job? = null
    private val frameChannel = Channel<ByteArray>(capacity = Channel.CONFLATED)

    // Hilo único dedicado de alta prioridad para mantener la afinidad del contexto EGL/OpenCL
    // del GpuDelegate de TensorFlow Lite y evitar penalizaciones de 30-50 ms por cambio de hilo.
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (ignored: Throwable) {}
            runnable.run()
        }, "OrionInferenceThread").apply { isDaemon = true }
    }
    private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()

    // Buffers pre-asignados con doble buffer (A/B) para cero presión sobre el Garbage Collector
    private val reusablePixels = IntArray(256 * 256)
    private val reusableNormDepth = FloatArray(256 * 256)
    private val cameraBitmaps = arrayOfNulls<Bitmap>(2)
    private val depthBitmaps: Array<Bitmap> by lazy {
        arrayOf(
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        )
    }

    init {
        scope.launch(inferenceDispatcher) {
            initEstimator(preferredBackend)
        }
    }

    private fun initEstimator(backend: InferenceBackend) {
        depthEstimator?.close()
        depthEstimator = try {
            val estimator = DepthEstimator(context, preferredBackend = backend)
            _state.update {
                it.copy(
                    activeBackend = estimator.activeBackend,
                    statusMessage = "IA Lista (${estimator.activeBackend})"
                )
            }
            estimator
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    statusMessage = "Error al iniciar TFLite: ${e.message}",
                    connectionStatus = ConnectionStatus.ERROR
                )
            }
            null
        }
    }

    fun switchBackend(backend: InferenceBackend) {
        preferredBackend = backend
        scope.launch(inferenceDispatcher) {
            initEstimator(backend)
        }
    }

    fun updateFeedbackSettings(settings: FeedbackSettings) {
        feedback.settings = settings
        _state.update { it.copy(feedbackSettings = settings) }
    }

    /**
     * Inicia el autodescubrimiento continuo y supervisor de reconexión.
     * Combina en paralelo el sondeo rápido HTTP (a la última IP conocida) y la escucha
     * de faros UDP en el puerto 9999, logrando enlaces instantáneos (<1.5s).
     */
    fun startAutoDiscovery() {
        stop(silent = true)
        pipelineJob = scope.launch(Dispatchers.IO) {
            var backoffMs = 500L
            while (isActive) {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.SEARCHING,
                        statusMessage = "Buscando cámara (sonda rápida o faro UDP)..."
                    )
                }

                // 1. Descubrir endpoint
                val endpoint = try {
                    findEndpoint()
                } catch (e: CancellationException) {
                    break
                }

                if (!isActive) break

                // 2. Transmitir stream desde el endpoint descubierto
                val streamOk = try {
                    streamFromEndpoint(endpoint)
                } catch (e: CancellationException) {
                    break
                }

                // 3. Si el stream se interrumpió y seguimos activos, esperar con backoff exponencial (0.5 -> 1 -> 2 -> 4s)
                if (isActive) {
                    if (streamOk) {
                        backoffMs = 500L
                    } else {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                    }
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.SEARCHING,
                            statusMessage = "Reintentando autoconexión (${backoffMs}ms)..."
                        )
                    }
                }
            }
        }
    }

    /**
     * Conexión directa a un endpoint conocido (por IP y puerto de stream).
     */
    fun connectDirect(ip: String, streamPort: Int = 81) {
        stop(silent = true)
        val endpoint = XiaoEndpoint(ip = ip.trim(), streamPort = streamPort)
        discovery.saveLastKnownEndpoint(endpoint)
        pipelineJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    streamFromEndpoint(endpoint)
                } catch (e: CancellationException) {
                    break
                }
                if (isActive) {
                    delay(1500)
                }
            }
        }
    }

    /**
     * Localiza el endpoint de la cámara combinando:
     * - Sondeo HTTP inmediato a la última IP conocida.
     * - Sondeo periódico concurrente (cada 1200 ms).
     * - Escucha pasiva continua de faros UDP (puerto 9999).
     */
    private suspend fun findEndpoint(): XiaoEndpoint = coroutineScope {
        val last = discovery.getLastKnownEndpoint()
        if (last != null && discovery.probe(last)) {
            return@coroutineScope last
        }

        val foundChannel = Channel<XiaoEndpoint>(Channel.CONFLATED)

        // Tarea A: Sondeo periódico de la última IP
        val probeJob = if (last != null) {
            launch(Dispatchers.IO) {
                while (isActive) {
                    delay(1200)
                    if (discovery.probe(last)) {
                        foundChannel.trySend(last)
                        break
                    }
                }
            }
        } else null

        // Tarea B: Escucha de faros UDP emitidos por el firmware
        val beaconJob = launch(Dispatchers.IO) {
            try {
                discovery.listenBeacons().collect { endpoint ->
                    foundChannel.trySend(endpoint)
                }
            } catch (e: CancellationException) {
                // Cancelación normal
            } catch (ignored: Exception) {
            }
        }

        try {
            foundChannel.receive()
        } finally {
            probeJob?.cancel()
            beaconJob.cancel()
        }
    }

    /**
     * Abre el flujo MJPEG desde el endpoint.
     * Anuncia "Cámara conectada" ÚNICAMENTE cuando se recibe exitosamente el primer frame de video,
     * eliminando cualquier bucle o falso positivo cuando la cámara está apagándose.
     */
    private suspend fun streamFromEndpoint(endpoint: XiaoEndpoint): Boolean {
        discovery.saveLastKnownEndpoint(endpoint)
        watchdog.reset()
        _state.update {
            it.copy(
                endpoint = endpoint,
                connectionStatus = ConnectionStatus.CONNECTING,
                watchdogState = WatchdogState.OK,
                statusMessage = "Conectando stream en ${endpoint.streamUrl}..."
            )
        }

        // Iniciar consumidor de inferencia sobre el hilo dedicado (preserva afinidad EGL de GpuDelegate)
        val consumerJob = scope.launch(inferenceDispatcher) {
            runInferenceLoop(endpoint)
        }

        var hasDeliveredFrame = false
        var watchdogTriggered = false

        // Supervisor activo del watchdog: detecta congelamiento del socket sin cierre TCP (>3500ms)
        val watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(400)
                if (hasDeliveredFrame) {
                    val ws = watchdog.check()
                    if (ws == WatchdogState.LOST) {
                        watchdogTriggered = true
                        feedback.onCameraLost()
                        _state.update {
                            it.copy(
                                watchdogState = WatchdogState.LOST,
                                statusMessage = "Watchdog: Sin frames en > 3.5s"
                            )
                        }
                        break
                    }
                }
            }
        }

        try {
            // Flujo productor de frames desde MJPEG
            streamReader.streamFrames(endpoint).collect { frameBytes ->
                if (watchdogTriggered) {
                    throw CancellationException("Watchdog: timeout de 3500 ms alcanzado")
                }
                watchdog.onFrame()
                if (!hasDeliveredFrame) {
                    hasDeliveredFrame = true
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.STREAMING,
                            watchdogState = WatchdogState.OK,
                            statusMessage = "Stream activo (${endpoint.ip})"
                        )
                    }
                    feedback.onCameraConnected()
                } else if (_state.value.watchdogState == WatchdogState.LOST) {
                    _state.update { it.copy(watchdogState = WatchdogState.OK) }
                }
                // Channel.CONFLATED descarta automáticamente frames acumulados si el consumidor está ocupado
                frameChannel.trySend(frameBytes)
            }
            return !watchdogTriggered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (hasDeliveredFrame && !watchdogTriggered) {
                feedback.onCameraDisconnected()
            }
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    statusMessage = "Stream interrumpido: ${e.message}"
                )
            }
            return false
        } finally {
            watchdogJob.cancel()
            consumerJob.cancel()
        }
    }

    /**
     * Bucle de procesamiento de frames en tiempo real (ejecutado siempre en OrionInferenceThread).
     */
    private suspend fun runInferenceLoop(endpoint: XiaoEndpoint?) {
        var frameCount = 0L
        var fpsLastTime = System.currentTimeMillis()
        var fpsFrameCounter = 0
        var currentFps = 0f

        while (scope.isActive) {
            val frameBytes = frameChannel.receive()
            val totalStart = System.nanoTime()
            val bufIdx = (frameCount % 2L).toInt()

            // 1. Decodificar JPEG reutilizando Bitmap pre-asignado (doble buffer A/B para cero GC)
            val decodeOptions = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inBitmap = cameraBitmaps[bufIdx]
            }
            val cameraBitmap = try {
                BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size, decodeOptions)
            } catch (e: Exception) {
                try {
                    BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                } catch (e2: Exception) {
                    null
                }
            } ?: continue
            cameraBitmaps[bufIdx] = cameraBitmap

            // 2. Preprocesamiento ImageNet (cero asignaciones de Bitmap)
            val inputBuffer = preprocessor.process(cameraBitmap)

            // 3. Inferencia MiDaS (mismo hilo nativo donde se inicializó GpuDelegate)
            val estimator = depthEstimator ?: continue
            val inferStart = System.nanoTime()
            val rawDepth = estimator.infer(inputBuffer)
            val inferEnd = System.nanoTime()
            val inferMs = (inferEnd - inferStart) / 1_000_000

            // 4. Normalización y segmentación espacial (reutilizando reusableNormDepth)
            val normDepth = DepthAnalyzer.normalize(rawDepth, outArray = reusableNormDepth)
            val zoneResult = DepthAnalyzer.analyze(normDepth, 256, 256)

            // 5. Retroalimentación sensorial (háptica, audio estéreo, voz)
            val alert = feedback.processFrameResult(zoneResult, endpoint)

            // 6. Generación de mapa de calor Magma en doble buffer (A/B)
            val colorizedBitmap = MagmaColorizer.toBitmap(
                depthNorm = normDepth,
                width = 256,
                height = 256,
                reuseBitmap = depthBitmaps[bufIdx],
                reusablePixels = reusablePixels
            )

            // 7. Cálculo de FPS y latencia total
            val totalEnd = System.nanoTime()
            val totalLatencyMs = (totalEnd - totalStart) / 1_000_000
            frameCount++
            fpsFrameCounter++

            // Control térmico de velocidad de frames (ThermalGovernor)
            val minInterval = thermalGovernor.minFrameIntervalMs
            if (minInterval > 0) {
                val computeMs = totalLatencyMs
                val remainingDelay = minInterval - computeMs
                if (remainingDelay > 0) {
                    delay(remainingDelay)
                }
            }

            val now = System.currentTimeMillis()
            val elapsedMs = now - fpsLastTime
            if (elapsedMs >= 1000) {
                currentFps = (fpsFrameCounter * 1000f) / elapsedMs
                fpsFrameCounter = 0
                fpsLastTime = now
            }

            _state.update {
                it.copy(
                    cameraBitmap = cameraBitmap,
                    depthBitmap = colorizedBitmap,
                    zoneResult = zoneResult,
                    activeAlert = alert,
                    inferenceMs = inferMs,
                    totalLatencyMs = totalLatencyMs,
                    fps = currentFps,
                    framesProcessed = frameCount,
                    activeBackend = estimator.activeBackend
                )
            }
        }
    }

    /**
     * Modo demostración / prueba sintética para validar el pipeline de IA
     * en el teléfono sin requerir la presencia inmediata del hardware XIAO.
     */
    fun startDemoMode() {
        stop()
        demoJob = scope.launch(inferenceDispatcher) {
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.DEMO_MODE,
                    statusMessage = "Modo Demo IA: Generando frames sintéticos..."
                )
            }

            var tick = 0
            val testBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(testBitmap)
            val bgPaint = Paint().apply { color = android.graphics.Color.DKGRAY }
            val circlePaint = Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true }

            var fpsLastTime = System.currentTimeMillis()
            var fpsFrameCounter = 0
            var currentFps = 0f
            var frameCount = 0L

            while (isActive) {
                val loopStart = System.nanoTime()
                val bufIdx = (frameCount % 2L).toInt()

                // Dibujar obstáculo simulado que se desplaza horizontalmente
                canvas.drawRect(0f, 0f, 256f, 256f, bgPaint)
                val posX = 64f + 64f * Math.sin(tick * 0.1).toFloat()
                val radius = 40f + 20f * Math.cos(tick * 0.05).toFloat()
                canvas.drawCircle(posX + 64f, 128f, radius, circlePaint)
                tick++

                // Inferencia en vivo con TFLite
                val inputBuffer = preprocessor.process(testBitmap)
                val estimator = depthEstimator
                if (estimator != null) {
                    val inferStart = System.nanoTime()
                    val rawDepth = estimator.infer(inputBuffer)
                    val inferMs = (System.nanoTime() - inferStart) / 1_000_000

                    val normDepth = DepthAnalyzer.normalize(rawDepth, outArray = reusableNormDepth)
                    val zoneResult = DepthAnalyzer.analyze(normDepth, 256, 256)
                    val alert = feedback.processFrameResult(zoneResult, null)

                    val colorized = MagmaColorizer.toBitmap(
                        depthNorm = normDepth,
                        width = 256,
                        height = 256,
                        reuseBitmap = depthBitmaps[bufIdx],
                        reusablePixels = reusablePixels
                    )

                    val totalLatencyMs = (System.nanoTime() - loopStart) / 1_000_000
                    frameCount++
                    fpsFrameCounter++

                    val now = System.currentTimeMillis()
                    val elapsedMs = now - fpsLastTime
                    if (elapsedMs >= 1000) {
                        currentFps = (fpsFrameCounter * 1000f) / elapsedMs
                        fpsFrameCounter = 0
                        fpsLastTime = now
                    }

                    _state.update {
                        it.copy(
                            cameraBitmap = testBitmap,
                            depthBitmap = colorized,
                            zoneResult = zoneResult,
                            activeAlert = alert,
                            inferenceMs = inferMs,
                            totalLatencyMs = totalLatencyMs,
                            fps = currentFps,
                            framesProcessed = frameCount,
                            activeBackend = estimator.activeBackend
                        )
                    }
                }

                delay(33) // ~30 FPS
            }
        }
    }

    /**
     * Ejecuta el benchmark de 50 inferencias en GPU y 50 en CPU directamente en el dispositivo móvil.
     * Reporta la mediana y el percentil 95 (p95) en milisegundos para ambos backends.
     */
    suspend fun runDeviceBenchmark(iterations: Int = 50): String = withContext(inferenceDispatcher) {
        _state.update { it.copy(statusMessage = "Ejecutando benchmark móvil ($iterations iteraciones)...") }

        val testBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(testBitmap)
        canvas.drawColor(android.graphics.Color.GRAY)

        val input = preprocessor.process(testBitmap)

        fun benchmarkBackend(backend: InferenceBackend): Pair<Double, Double> {
            val estimator = DepthEstimator(context, preferredBackend = backend)
            // Warmup (3 iteraciones)
            repeat(3) { estimator.infer(input) }

            val times = DoubleArray(iterations)
            for (i in 0 until iterations) {
                val start = System.nanoTime()
                estimator.infer(input)
                val durationMs = (System.nanoTime() - start) / 1_000_000.0
                times[i] = durationMs
            }
            estimator.close()
            times.sort()
            val median = times[iterations / 2]
            val p95 = times[(iterations * 0.95).toInt()]
            return Pair(median, p95)
        }

        val (gpuMed, gpuP95) = try {
            benchmarkBackend(InferenceBackend.GPU)
        } catch (e: Exception) {
            Pair(-1.0, -1.0)
        }

        val (cpuMed, cpuP95) = benchmarkBackend(InferenceBackend.CPU)

        // Restaurar backend preferido
        initEstimator(preferredBackend)

        val gpuStr = if (gpuMed > 0) {
            val gpuFps = String.format("%.1f", 1000.0 / gpuMed)
            "GPU: Mediana ${String.format("%.1f", gpuMed)} ms (p95: ${String.format("%.1f", gpuP95)} ms, ~$gpuFps FPS)"
        } else {
            "GPU: No disponible en este dispositivo"
        }

        val cpuFps = String.format("%.1f", 1000.0 / cpuMed)
        val cpuStr = "CPU (4 hilos): Mediana ${String.format("%.1f", cpuMed)} ms (p95: ${String.format("%.1f", cpuP95)} ms, ~$cpuFps FPS)"

        val resultSummary = "$gpuStr\n$cpuStr"
        _state.update {
            it.copy(
                benchmarkSummary = resultSummary,
                statusMessage = "Benchmark completado"
            )
        }
        resultSummary
    }

    fun stop(silent: Boolean = false) {
        val wasConnected = _state.value.connectionStatus == ConnectionStatus.STREAMING ||
                _state.value.connectionStatus == ConnectionStatus.CONNECTING
        pipelineJob?.cancel()
        pipelineJob = null
        demoJob?.cancel()
        demoJob = null
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.IDLE,
                statusMessage = "Detenido"
            )
        }
        if (wasConnected && !silent) {
            feedback.onCameraDisconnected()
        }
    }

    override fun close() {
        stop(silent = true)
        thermalGovernor.close()
        feedback.close()
        try {
            inferenceExecutor.submit {
                depthEstimator?.close()
                depthEstimator = null
            }.get(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (ignored: Exception) {
            depthEstimator?.close()
        }
        inferenceDispatcher.close()
    }
}
