package com.audacia.orion.vision

import android.content.Context
import android.content.res.AssetFileDescriptor
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
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

    var activeBackend: InferenceBackend = InferenceBackend.CPU
        private set

    private var gpuDelegate: GpuDelegate? = null
    private val interpreter: Interpreter

    val outputWidth = 256
    val outputHeight = 256

    private val outputBuffer: FloatBuffer = ByteBuffer.allocateDirect(1 * outputWidth * outputHeight * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val rawArray = FloatArray(outputWidth * outputHeight)

    init {
        val modelBuffer = loadModelFile(context, modelAssetName)
        val options = Interpreter.Options()

        var initializedGpu = false
        if (preferredBackend == InferenceBackend.GPU) {
            try {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    val delegate = GpuDelegate(delegateOptions)
                    options.addDelegate(delegate)
                    gpuDelegate = delegate
                    activeBackend = InferenceBackend.GPU
                    initializedGpu = true
                }
            } catch (e: Exception) {
                gpuDelegate = null
                initializedGpu = false
            }
        }

        if (!initializedGpu) {
            options.setNumThreads(4)
            activeBackend = InferenceBackend.CPU
        }

        interpreter = Interpreter(modelBuffer, options)
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
