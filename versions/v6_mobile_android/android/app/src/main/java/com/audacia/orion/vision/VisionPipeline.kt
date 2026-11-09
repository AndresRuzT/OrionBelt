package com.audacia.orion.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import com.audacia.orion.net.MjpegStreamReader
import com.audacia.orion.net.XiaoDiscovery
import com.audacia.orion.net.XiaoEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var pipelineJob: Job? = null
    private var demoJob: Job? = null
    private val frameChannel = Channel<ByteArray>(capacity = Channel.CONFLATED)

    private val reusablePixels = IntArray(256 * 256)
    private var depthBitmap: Bitmap? = null

    init {
        initEstimator(preferredBackend)
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
        initEstimator(backend)
    }

    /**
     * Inicia el autodescubrimiento por UDP y se conecta automáticamente al encontrar el XIAO.
     * Si existe una IP previa guardada, prueba primero un sondeo rápido (probe HTTP).
     */
    fun startAutoDiscovery() {
        stop()
        pipelineJob = scope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.SEARCHING,
                    statusMessage = "Buscando cámara (última IP o faro UDP)..."
                )
            }

            // 1. Probar primero la última IP conocida para conexión instantánea
            val last = discovery.getLastKnownEndpoint()
            if (last != null) {
                val ok = discovery.probe(last)
                if (ok && isActive) {
                    _state.update {
                        it.copy(
                            endpoint = last,
                            connectionStatus = ConnectionStatus.CONNECTING,
                            statusMessage = "Cámara reconocida en ${last.ip}. Conectando..."
                        )
                    }
                    connectToEndpoint(last)
                    return@launch
                }
            }

            // 2. Escuchar faros UDP emitidos por el XIAO en el puerto 9999
            try {
                val endpoint = discovery.listenBeacons().first()
                _state.update {
                    it.copy(
                        endpoint = endpoint,
                        connectionStatus = ConnectionStatus.CONNECTING,
                        statusMessage = "Faro recibido desde ${endpoint.ip}. Conectando stream..."
                    )
                }
                connectToEndpoint(endpoint)
            } catch (e: CancellationException) {
                // Cancelación normal
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.ERROR,
                        statusMessage = "Fallo en descubrimiento: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Conexión directa a un endpoint conocido (por IP y puerto de stream).
     */
    fun connectDirect(ip: String, streamPort: Int = 81) {
        stop()
        val endpoint = XiaoEndpoint(ip = ip.trim(), streamPort = streamPort)
        discovery.saveLastKnownEndpoint(endpoint)
        pipelineJob = scope.launch(Dispatchers.IO) {
            connectToEndpoint(endpoint)
        }
    }

    private suspend fun connectToEndpoint(endpoint: XiaoEndpoint) {
        discovery.saveLastKnownEndpoint(endpoint)
        _state.update {
            it.copy(
                endpoint = endpoint,
                connectionStatus = ConnectionStatus.CONNECTING,
                statusMessage = "Abriendo stream en ${endpoint.streamUrl}..."
            )
        }

        // Iniciar consumidor de inferencia
        val consumerJob = scope.launch(Dispatchers.Default) {
            runInferenceLoop()
        }

        try {
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.STREAMING,
                    statusMessage = "Stream activo (${endpoint.ip})"
                )
            }

            // Flujo productor de frames desde MJPEG
            streamReader.streamFrames(endpoint).collect { frameBytes ->
                // Channel.CONFLATED descarta automáticamente frames acumulados si el consumidor está ocupado
                frameChannel.trySend(frameBytes)
            }
        } catch (e: CancellationException) {
            // Cancelado intencionalmente
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    statusMessage = "Stream interrumpido: ${e.message}"
                )
            }
        } finally {
            consumerJob.cancel()
        }
    }

    /**
     * Bucle de procesamiento de frames en tiempo real.
     */
    private suspend fun runInferenceLoop() {
        var frameCount = 0L
        var fpsLastTime = System.currentTimeMillis()
        var fpsFrameCounter = 0
        var currentFps = 0f

        while (scope.isActive) {
            val frameBytes = frameChannel.receive()
            val totalStart = System.nanoTime()

            // 1. Decodificar JPEG
            val cameraBitmap = try {
                BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
            } catch (e: Exception) {
                null
            } ?: continue

            // 2. Preprocesamiento ImageNet
            val inputBuffer = preprocessor.process(cameraBitmap)

            // 3. Inferencia MiDaS
            val estimator = depthEstimator ?: continue
            val inferStart = System.nanoTime()
            val rawDepth = estimator.infer(inputBuffer)
            val inferEnd = System.nanoTime()
            val inferMs = (inferEnd - inferStart) / 1_000_000

            // 4. Normalización y segmentación espacial
            val normDepth = DepthAnalyzer.normalize(rawDepth)
            val zoneResult = DepthAnalyzer.analyze(normDepth, 256, 256)

            // 5. Generación de mapa de calor Magma
            val colorizedBitmap = MagmaColorizer.toBitmap(
                depthNorm = normDepth,
                width = 256,
                height = 256,
                reuseBitmap = depthBitmap,
                reusablePixels = reusablePixels
            )
            depthBitmap = colorizedBitmap

            // 6. Cálculo de FPS y latencia total
            val totalEnd = System.nanoTime()
            val totalLatencyMs = (totalEnd - totalStart) / 1_000_000
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
                    cameraBitmap = cameraBitmap,
                    depthBitmap = colorizedBitmap,
                    zoneResult = zoneResult,
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
        demoJob = scope.launch(Dispatchers.Default) {
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

                    val normDepth = DepthAnalyzer.normalize(rawDepth)
                    val zoneResult = DepthAnalyzer.analyze(normDepth, 256, 256)

                    val colorized = MagmaColorizer.toBitmap(
                        depthNorm = normDepth,
                        width = 256,
                        height = 256,
                        reuseBitmap = depthBitmap,
                        reusablePixels = reusablePixels
                    )
                    depthBitmap = colorized

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
    suspend fun runDeviceBenchmark(iterations: Int = 50): String = withContext(Dispatchers.Default) {
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

    fun stop() {
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
    }

    override fun close() {
        stop()
        depthEstimator?.close()
    }
}
