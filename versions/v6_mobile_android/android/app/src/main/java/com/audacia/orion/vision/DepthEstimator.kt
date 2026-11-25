package com.audacia.orion.vision

import android.content.Context
import android.content.res.AssetFileDescriptor
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

enum class InferenceBackend {
    GPU,
    CPU
}

/**
 * Motor de inferencia para MiDaS Small cuantizado en FP16 (ORION v6).
 *
 * Utiliza aceleración por GPU móvil si el hardware es compatible (CompatibilityList).
 * En caso contrario, realiza fallback suave a 4 hilos en CPU con XNNPACK.
 */
class DepthEstimator(
    context: Context,
    modelAssetName: String = "midas_small_256_fp16.tflite",
    preferredBackend: InferenceBackend = InferenceBackend.GPU
) : AutoCloseable {

    companion object {
        private const val TAG = "OrionDepthEstimator"
    }

    private var gpuDelegate: GpuDelegate? = null
    private val interpreter: Interpreter

    val outputWidth = 256
    val outputHeight = 256

    private val outputBuffer: FloatBuffer = ByteBuffer.allocateDirect(1 * outputWidth * outputHeight * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val rawArray = FloatArray(outputWidth * outputHeight)
    var activeBackend: InferenceBackend = InferenceBackend.CPU
        private set
    var activeGpuEngine: String? = null
        private set

    init {
        val modelBuffer = loadModelFile(context, modelAssetName)
        var activeDelegate: GpuDelegate? = null
        var activeInterpreter: Interpreter? = null
        var backendResolved = InferenceBackend.CPU
        var engineResolved: String? = null

        if (preferredBackend == InferenceBackend.GPU) {
            // Estrategia de inicialización GPU en cascada:
            // 1. OpenCL / Auto con FP16 (máximo rendimiento en chips Adreno/Mali)
            // 2. OpenGL ES Compute Shaders explícito (estándar universal en Android 14/15,
            //    inmune a restricciones de permisos en libOpenCL.so en Motorola Edge)
            // 3. OpenGL ES con precisión completa FP32
            val gpuAttempts = listOf(
                Pair("OpenCL/Auto FP16", GpuDelegateFactory.Options().apply {
                    setPrecisionLossAllowed(true)
                    setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                }),
                Pair("OpenGL ES FP16", GpuDelegateFactory.Options().apply {
                    setForceBackend(GpuDelegateFactory.Options.GpuBackend.OPENGL)
                    setPrecisionLossAllowed(true)
                    setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                }),
                Pair("OpenGL ES FP32", GpuDelegateFactory.Options().apply {
                    setForceBackend(GpuDelegateFactory.Options.GpuBackend.OPENGL)
                    setPrecisionLossAllowed(false)
                })
            )

            for ((engineName, options) in gpuAttempts) {
                var candidateDelegate: GpuDelegate? = null
                var candidateInterpreter: Interpreter? = null
                try {
                    android.util.Log.i(TAG, "Intentando inicializar aceleración GPU con $engineName...")
                    candidateDelegate = GpuDelegate(options)
                    val gpuOptions = Interpreter.Options().apply {
                        addDelegate(candidateDelegate)
                    }

                    // 1. Instanciar intérprete con aceleración GPU
                    val testInterpreter = Interpreter(modelBuffer.duplicate(), gpuOptions)

                    // 2. Verificación de ejecución: forzar compilación de shaders en GPU con un buffer de prueba
                    val testInput = ByteBuffer.allocateDirect(1 * outputWidth * outputHeight * 3 * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                    outputBuffer.rewind()
                    testInterpreter.run(testInput, outputBuffer)
                    outputBuffer.rewind()

                    activeDelegate = candidateDelegate
                    activeInterpreter = testInterpreter
                    backendResolved = InferenceBackend.GPU
                    engineResolved = engineName
                    android.util.Log.i(TAG, "¡Aceleración GPU inicializada exitosamente con $engineName!")
                    break
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "Fallo al inicializar GPU con $engineName: ${t.message}", t)
                    try {
                        candidateInterpreter?.close()
                    } catch (ignored: Throwable) {}
                    try {
                        candidateDelegate?.close()
                    } catch (ignored: Throwable) {}
                }
            }
        }

        // Si se seleccionó CPU o si todos los motores GPU fallaron, inicializar CPU con XNNPACK
        if (activeInterpreter == null) {
            android.util.Log.i(TAG, "Iniciando motor CPU con aceleración XNNPACK...")
            val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val cpuOptions = Interpreter.Options().apply {
                setUseXNNPACK(true)
                setNumThreads(cpuThreads)
            }
            activeInterpreter = Interpreter(modelBuffer.duplicate(), cpuOptions)
            backendResolved = InferenceBackend.CPU
            engineResolved = "CPU (XNNPACK)"
        }

        gpuDelegate = activeDelegate
        interpreter = activeInterpreter
        activeBackend = backendResolved
        activeGpuEngine = engineResolved
    }

    private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(assetName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun infer(inputBuffer: FloatBuffer): FloatArray {
        inputBuffer.rewind()
        outputBuffer.rewind()

        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        outputBuffer.get(rawArray)
        return rawArray
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
