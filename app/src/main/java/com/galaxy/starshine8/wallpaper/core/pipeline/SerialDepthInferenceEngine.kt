package com.galaxy.starshine8.wallpaper.core.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.hypot

/**
 * 串行化端侧 INT8 深度推理引擎（SerialDepthInferenceEngine）。
 * P1 优化：移除 finally 块中易引发主线程微卡顿的显式 显式 GC 调用，依赖 ART 并发回收与显式资源 close()/recycle()。
 */
class SerialDepthInferenceEngine(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {

    companion object {
        const val MODEL_ASSET_PATH = "models/depth_anything_v2_small_int8.onnx"
        const val MODEL_INPUT_WIDTH = 518
        const val MODEL_INPUT_HEIGHT = 336
        const val DEPTH_OUTPUT_WIDTH = 640
        const val DEPTH_OUTPUT_HEIGHT = 400
        private const val INFERENCE_TIMEOUT_MS = 800L
    }

    private data class DepthTask(
        val sourceBitmap: Bitmap,
        val resultDeferred: CompletableDeferred<Bitmap>
    )

    private val inferenceMutex = Mutex()
    private val taskChannel = Channel<DepthTask>(capacity = 2)

    init {
        coroutineScope.launch(Dispatchers.Default) {
            for (task in taskChannel) {
                val depthBmp = inferenceMutex.withLock {
                    executeSingleInferenceWithFallback(task.sourceBitmap)
                }
                task.resultDeferred.complete(depthBmp)
            }
        }
    }

    suspend fun inferDepthMap640x400(sourceBitmap2560x1600: Bitmap): Bitmap {
        val deferred = CompletableDeferred<Bitmap>()
        val sent = taskChannel.trySend(DepthTask(sourceBitmap2560x1600, deferred)).isSuccess
        return if (sent) {
            deferred.await()
        } else {
            inferenceMutex.withLock {
                executeSingleInferenceWithFallback(sourceBitmap2560x1600)
            }
        }
    }

    fun shutdown() {
        taskChannel.close()
    }

    private suspend fun executeSingleInferenceWithFallback(sourceBitmap: Bitmap): Bitmap {
        val onnxResult = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
            runCatching {
                runOnnxInt8DepthInference(sourceBitmap)
            }.getOrNull()
        }
        return onnxResult ?: generateProceduralHorizonRadialDepth(sourceBitmap)
    }

    private fun runOnnxInt8DepthInference(sourceBitmap: Bitmap): Bitmap? {
        val modelExists = runCatching {
            context.assets.list("models")?.contains("depth_anything_v2_small_int8.onnx") == true
        }.getOrDefault(false)
        if (!modelExists) {
            return null
        }

        val cachedModelFile = File(context.cacheDir, "depth_anything_v2_small_int8.onnx")
        if (!cachedModelFile.exists() || cachedModelFile.length() == 0L) {
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                FileOutputStream(cachedModelFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        val scaledInput = Bitmap.createScaledBitmap(
            sourceBitmap,
            MODEL_INPUT_WIDTH,
            MODEL_INPUT_HEIGHT,
            true
        )

        var ortEnv: OrtEnvironment? = null
        var ortSession: OrtSession? = null
        var inputTensor: OnnxTensor? = null

        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnv.createSession(cachedModelFile.absolutePath, sessionOptions)

            val floatBuffer = preprocessBitmapToChwFloatBuffer(scaledInput)
            val shape = longArrayOf(1L, 3L, MODEL_INPUT_HEIGHT.toLong(), MODEL_INPUT_WIDTH.toLong())
            inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)

            val inputName = ortSession.inputNames.first()
            ortSession.run(mapOf(inputName to inputTensor)).use { results ->
                val outputTensor = results[0] as? OnnxTensor ?: return null
                val rawDepthBuffer = outputTensor.floatBuffer ?: return null
                val depth518x336 = convertFloatDepthBufferToBitmap(
                    rawDepthBuffer,
                    MODEL_INPUT_WIDTH,
                    MODEL_INPUT_HEIGHT
                )
                val final640x400 = Bitmap.createScaledBitmap(
                    depth518x336,
                    DEPTH_OUTPUT_WIDTH,
                    DEPTH_OUTPUT_HEIGHT,
                    true
                )
                if (final640x400 !== depth518x336) {
                    depth518x336.recycle()
                }
                return final640x400
            }
        } finally {
            if (scaledInput !== sourceBitmap && !scaledInput.isRecycled) {
                scaledInput.recycle()
            }
            runCatching { inputTensor?.close() }
            runCatching { ortSession?.close() }
        }
    }

    private fun preprocessBitmapToChwFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val buffer = FloatBuffer.allocate(3 * w * h)
        val planeSize = w * h
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        for (i in 0 until planeSize) {
            val c = pixels[i]
            val r = ((c shr 16 and 0xFF) / 255.0f - mean[0]) / std[0]
            val g = ((c shr 8 and 0xFF) / 255.0f - mean[1]) / std[1]
            val b = ((c and 0xFF) / 255.0f - mean[2]) / std[2]
            buffer.put(i, r)
            buffer.put(planeSize + i, g)
            buffer.put(planeSize * 2 + i, b)
        }
        buffer.rewind()
        return buffer
    }

    private fun convertFloatDepthBufferToBitmap(
        depthBuffer: FloatBuffer,
        width: Int,
        height: Int
    ): Bitmap {
        val total = width * height
        val values = FloatArray(total)
        depthBuffer.rewind()
        depthBuffer.get(values, 0, minOf(total, depthBuffer.remaining()))

        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE
        for (v in values) {
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        val span = (maxVal - minVal).coerceAtLeast(1e-5f)
        val outPixels = IntArray(total)
        for (i in 0 until total) {
            val norm = ((values[i] - minVal) / span * 255.0f).toInt().coerceIn(0, 255)
            outPixels[i] = Color.rgb(norm, norm, norm)
        }
        return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun generateProceduralHorizonRadialDepth(sourceBitmap: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(
            sourceBitmap,
            DEPTH_OUTPUT_WIDTH,
            DEPTH_OUTPUT_HEIGHT,
            true
        )
        val w = DEPTH_OUTPUT_WIDTH
        val h = DEPTH_OUTPUT_HEIGHT
        val srcPixels = IntArray(w * h)
        scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)
        if (scaled !== sourceBitmap) {
            scaled.recycle()
        }

        val outPixels = IntArray(w * h)
        for (y in 0 until h) {
            val normY = y / (h - 1.0f)
            val horizonGradient = normY * 0.55f
            for (x in 0 until w) {
                val normX = x / (w - 1.0f)
                val distCenter = hypot((normX - 0.5f) * 0.85f, (normY - 0.56f) * 1.15f)
                val radialFocus = (1.0f - distCenter * 1.25f).coerceIn(0.0f, 1.0f)

                val c = srcPixels[y * w + x]
                val r = (c shr 16 and 0xFF) / 255.0f
                val g = (c shr 8 and 0xFF) / 255.0f
                val b = (c and 0xFF) / 255.0f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b

                val combinedDepth = (horizonGradient * 0.48f + radialFocus * 0.37f + luma * 0.15f)
                    .coerceIn(0.0f, 1.0f)
                val gray8Bit = (combinedDepth * 255.0f).toInt().coerceIn(0, 255)
                outPixels[y * w + x] = Color.rgb(gray8Bit, gray8Bit, gray8Bit)
            }
        }
        return Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
