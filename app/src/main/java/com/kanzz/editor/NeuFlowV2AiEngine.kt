package com.kanzz.editor

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** Dense motion field estimated by the on-device neural model. */
data class AiMotionField(
    val width: Int,
    val height: Int,
    val x: FloatArray,
    val y: FloatArray,
    val meanMagnitude: Float,
    val maxMagnitude: Float,
    val activeRatio: Float,
    val inferenceMs: Long
)

/**
 * NeuFlowV2 ONNX model distribution.
 * The model is fetched only when AI Motion Blur is first used, not at app startup.
 */
object NeuFlowV2Model {
    const val MODEL_FILE = "neuflow_mixed.onnx"
    const val MODEL_URL =
        "https://github.com/ibaiGorordo/ONNX-NeuFlowV2-Optical-Flow/releases/download/0.1.0/neuflow_mixed.onnx"
    const val MODEL_SIZE_BYTES = 42_202_229L

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "ai_models"), MODEL_FILE)

    /** Call from a worker thread. Partial downloads are never accepted as models. */
    fun ensureDownloaded(
        context: Context,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        val target = modelFile(context)
        if (target.isFile && target.length() == MODEL_SIZE_BYTES) return target
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        partial.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CAPCAKE-Android")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Unduhan model gagal: HTTP ${connection.responseCode}")
            }
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, MODEL_SIZE_BYTES)
                    }
                    output.fd.sync()
                }
            }
            if (downloaded != MODEL_SIZE_BYTES) {
                partial.delete()
                throw IllegalStateException(
                    "Ukuran model tidak sesuai: $downloaded dari $MODEL_SIZE_BYTES byte"
                )
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            return target
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * NeuFlowV2 dense optical-flow inference.
 * Instantiate and call only on the background render worker.
 */
class NeuFlowV2AiEngine(model: File) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputNames: List<String>
    private val inputWidth: Int
    private val inputHeight: Int

    init {
        require(model.isFile && model.length() == NeuFlowV2Model.MODEL_SIZE_BYTES) {
            "Model NeuFlowV2 belum tersedia atau tidak lengkap"
        }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2))
        }
        session = environment.createSession(model.absolutePath, options)
        options.close()

        inputNames = session.inputNames.toList()
        require(inputNames.size >= 2) { "Model harus menerima dua frame input" }
        val firstInfo = session.inputInfo[inputNames[0]]?.info as? TensorInfo
            ?: error("Input pertama bukan tensor")
        val shape = firstInfo.shape
        require(shape.size == 4 && shape[1] == 3L && shape[2] > 0L && shape[3] > 0L) {
            "Bentuk input NeuFlowV2 tidak didukung: ${shape.contentToString()}"
        }
        inputHeight = shape[2].toInt()
        inputWidth = shape[3].toInt()
    }

    fun estimate(previous: Bitmap, current: Bitmap): AiMotionField {
        val inferenceStartedNs = System.nanoTime()
        require(previous.width == current.width && previous.height == current.height) {
            "Ukuran frame harus sama"
        }
        val previousInput = imageToNchw(previous)
        val currentInput = imageToNchw(current)
        val firstTensor = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(previousInput),
            longArrayOf(1L, 3L, inputHeight.toLong(), inputWidth.toLong())
        )
        val secondTensor = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(currentInput),
            longArrayOf(1L, 3L, inputHeight.toLong(), inputWidth.toLong())
        )

        try {
            val inputs = linkedMapOf(
                inputNames[0] to firstTensor,
                inputNames[1] to secondTensor
            )
            session.run(inputs).use { result ->
                val output = result.get(0).value as? OnnxTensor
                    ?: error("Output optical flow bukan tensor")
                val dimensions = output.info.shape
                require(dimensions.size == 4) {
                    "Dimensi output flow tidak didukung: ${dimensions.contentToString()}"
                }
                val channelFirst: Boolean
                val flowHeight: Int
                val flowWidth: Int
                if (dimensions[1] == 2L) {
                    channelFirst = true
                    flowHeight = dimensions[2].toInt()
                    flowWidth = dimensions[3].toInt()
                } else if (dimensions[3] == 2L) {
                    channelFirst = false
                    flowHeight = dimensions[1].toInt()
                    flowWidth = dimensions[2].toInt()
                } else {
                    error("Output NeuFlowV2 tidak memiliki dua channel flow")
                }

                val values = output.floatBuffer
                values.rewind()
                val expected = flowWidth * flowHeight * 2
                require(values.remaining() >= expected) { "Data output flow terpotong" }
                val raw = FloatArray(expected)
                values.get(raw)

                val targetWidth = current.width
                val targetHeight = current.height
                val outX = FloatArray(targetWidth * targetHeight)
                val outY = FloatArray(targetWidth * targetHeight)
                val scaleX = targetWidth.toFloat() / flowWidth
                val scaleY = targetHeight.toFloat() / flowHeight

                fun component(index: Int, channel: Int): Float {
                    val offset = if (channelFirst) {
                        channel * flowWidth * flowHeight + index
                    } else {
                        index * 2 + channel
                    }
                    return raw[offset]
                }

                for (y in 0 until targetHeight) {
                    val sy = (y.toFloat() / scaleY).coerceIn(0f, (flowHeight - 1).toFloat())
                    val y0 = sy.toInt()
                    val y1 = min(y0 + 1, flowHeight - 1)
                    val fy = sy - y0
                    for (x in 0 until targetWidth) {
                        val sx = (x.toFloat() / scaleX).coerceIn(0f, (flowWidth - 1).toFloat())
                        val x0 = sx.toInt()
                        val x1 = min(x0 + 1, flowWidth - 1)
                        val fx = sx - x0
                        val i00 = y0 * flowWidth + x0
                        val i10 = y0 * flowWidth + x1
                        val i01 = y1 * flowWidth + x0
                        val i11 = y1 * flowWidth + x1
                        val outIndex = y * targetWidth + x
                        val vx0 = component(i00, 0) * (1f - fx) + component(i10, 0) * fx
                        val vx1 = component(i01, 0) * (1f - fx) + component(i11, 0) * fx
                        val vy0 = component(i00, 1) * (1f - fx) + component(i10, 1) * fx
                        val vy1 = component(i01, 1) * (1f - fx) + component(i11, 1) * fx
                        // Reference ONNX wrapper resizes the flow field without scaling vector magnitudes.
                        outX[outIndex] = vx0 * (1f - fy) + vx1 * fy
                        outY[outIndex] = vy0 * (1f - fy) + vy1 * fy
                    }
                }
                var magnitudeSum = 0.0
                var maximumMagnitude = 0f
                var activePixels = 0
                for (i in outX.indices) {
                    val magnitude = kotlin.math.sqrt(outX[i] * outX[i] + outY[i] * outY[i])
                    magnitudeSum += magnitude.toDouble()
                    if (magnitude > maximumMagnitude) maximumMagnitude = magnitude
                    if (magnitude > 1f) activePixels++
                }
                val inferenceMs = (System.nanoTime() - inferenceStartedNs) / 1_000_000L
                val meanMagnitude = (magnitudeSum / outX.size.coerceAtLeast(1)).toFloat()
                val activeRatio = activePixels.toFloat() / outX.size.coerceAtLeast(1)
                return AiMotionField(targetWidth, targetHeight, outX, outY,
                    meanMagnitude, maximumMagnitude, activeRatio, inferenceMs)
            }
        } finally {
            firstTensor.close()
            secondTensor.close()
        }
    }

    private fun imageToNchw(source: Bitmap): FloatArray {
        val scaled = if (source.width == inputWidth && source.height == inputHeight) {
            source
        } else {
            Bitmap.createScaledBitmap(source, inputWidth, inputHeight, true)
        }
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (scaled !== source) scaled.recycle()

        val planeSize = inputWidth * inputHeight
        val tensor = FloatArray(planeSize * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            // Match the reference OpenCV preprocessing channel order (BGR).
            tensor[i] = (pixel and 0xFF) / 255f
            tensor[planeSize + i] = ((pixel shr 8) and 0xFF) / 255f
            tensor[planeSize * 2 + i] = ((pixel shr 16) and 0xFF) / 255f
        }
        return tensor
    }

    override fun close() {
        session.close()
    }
}
