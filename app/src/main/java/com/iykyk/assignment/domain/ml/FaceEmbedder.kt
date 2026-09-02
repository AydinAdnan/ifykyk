package com.iykyk.assignment.domain.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

interface FaceEmbedder {
    /** Side length, in pixels, of the aligned square crop this model expects. */
    val inputSize: Int

    fun getEmbedding(faceBitmap: Bitmap?): FloatArray
    fun cosineSimilarity(u: FloatArray, v: FloatArray): Float
}

/**
 * How pixel values are mapped into the tensor the network was trained on.
 *
 * Getting this wrong does not fail loudly - the model still emits a vector - but the
 * embedding space collapses and unrelated faces end up with similar vectors, which is
 * indistinguishable from "the clustering thresholds are badly tuned".
 */
private enum class Normalization {
    /** FaceNet / Inception-ResNet-V1: per-image standardisation ("prewhiten"). */
    PREWHITEN,

    /** MobileFaceNet / ArcFace family: fixed (x - 127.5) / 128 into [-1, 1]. */
    FIXED
}

class TFLiteFaceEmbedder(private val context: Context? = null) : FaceEmbedder {

    private var interpreter: Interpreter? = null
    private var normalization = Normalization.PREWHITEN
    private var outputDim = FALLBACK_DIM

    override var inputSize: Int = 160
        private set

    companion object {
        private const val TAG = "FaceEmbedder"
        private const val FALLBACK_DIM = 512
    }

    init {
        context?.let { ctx -> loadModel(ctx) }
    }

    private fun loadModel(ctx: Context) {
        try {
            val assetManager = ctx.assets
            val preferred = listOf("mobilefacenet.tflite", "facenet.tflite")
            var afd: android.content.res.AssetFileDescriptor? = null
            var loadedName = ""

            for (name in preferred) {
                try {
                    afd = assetManager.openFd(name)
                    loadedName = name
                    break
                } catch (ignored: Exception) {
                }
            }

            if (afd == null) {
                val tfliteFile = (assetManager.list("") ?: emptyArray())
                    .firstOrNull { it.endsWith(".tflite") }
                if (tfliteFile != null) {
                    afd = assetManager.openFd(tfliteFile)
                    loadedName = tfliteFile
                }
            }

            if (afd == null) {
                android.util.Log.w(TAG, "No .tflite model in assets; using deterministic extractor.")
                return
            }

            val modelBuffer = FileInputStream(afd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
            // XNNPACK is the difference between a usable and an unusable latency for a
            // float Inception-ResNet-V1 on CPU. It is the default for float models on
            // recent TFLite, but is asked for explicitly so a runtime change cannot
            // silently halve throughput.
            val options = Interpreter.Options().apply {
                setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                try {
                    setUseXNNPACK(true)
                } catch (e: Throwable) {
                    android.util.Log.w(TAG, "XNNPACK unavailable; running the default CPU kernels")
                }
            }
            val interp = Interpreter(modelBuffer, options)

            val inputShape = interp.getInputTensor(0).shape()
            if (inputShape.size >= 3) inputSize = inputShape[1]

            val outShape = interp.getOutputTensor(0).shape()
            outputDim = if (outShape.isNotEmpty()) outShape.last() else FALLBACK_DIM

            // The bundled asset is named "mobilefacenet" but is actually a FaceNet
            // Inception-ResNet-V1 export (160x160 in, 128-d out). Choose preprocessing
            // from the tensor geometry rather than trusting the filename.
            normalization = if (inputSize >= 150) Normalization.PREWHITEN else Normalization.FIXED

            interpreter = interp
            android.util.Log.i(
                TAG,
                "Loaded $loadedName: ${inputSize}x$inputSize -> ${outputDim}d, norm=$normalization"
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load TFLite interpreter", e)
            interpreter = null
        }
    }

    override fun getEmbedding(faceBitmap: Bitmap?): FloatArray {
        if (faceBitmap == null) return FloatArray(outputDim)
        val interp = interpreter ?: return computeDeterministicFeatureVector(faceBitmap)

        // Flip averaging: summing the embedding of the crop and of its mirror cancels a
        // good deal of pose and lighting noise, which tightens same-person similarity.
        val direct = runInference(interp, faceBitmap, mirrored = false)
            ?: return computeDeterministicFeatureVector(faceBitmap)
        val flipped = runInference(interp, faceBitmap, mirrored = true)
            ?: return l2Normalize(direct)

        val summed = FloatArray(direct.size)
        for (i in direct.indices) summed[i] = direct[i] + flipped[i]
        return l2Normalize(summed)
    }

    private fun runInference(interp: Interpreter, bitmap: Bitmap, mirrored: Boolean): FloatArray? {
        return try {
            val size = inputSize
            val scaled = if (bitmap.width == size && bitmap.height == size) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, size, size, true)
            }

            val pixels = IntArray(size * size)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            if (scaled !== bitmap) scaled.recycle()

            val channels = FloatArray(size * size * 3)
            var w = 0
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val sx = if (mirrored) size - 1 - x else x
                    val pixel = pixels[y * size + sx]
                    channels[w++] = ((pixel shr 16) and 0xFF).toFloat()
                    channels[w++] = ((pixel shr 8) and 0xFF).toFloat()
                    channels[w++] = (pixel and 0xFF).toFloat()
                }
            }

            applyNormalization(channels)

            val inputBuffer = ByteBuffer
                .allocateDirect(channels.size * 4)
                .order(ByteOrder.nativeOrder())
            for (value in channels) inputBuffer.putFloat(value)
            inputBuffer.rewind()

            val outputArray = Array(1) { FloatArray(outputDim) }
            interp.run(inputBuffer, outputArray)
            outputArray[0]
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Inference failed", e)
            null
        }
    }

    private fun applyNormalization(channels: FloatArray) {
        when (normalization) {
            Normalization.FIXED -> {
                for (i in channels.indices) channels[i] = (channels[i] - 127.5f) / 128f
            }

            Normalization.PREWHITEN -> {
                // y = (x - mean) / max(std, 1/sqrt(n)), matching FaceNet prewhitening.
                var sum = 0.0
                for (v in channels) sum += v
                val mean = (sum / channels.size).toFloat()

                var sqSum = 0.0
                for (v in channels) {
                    val d = (v - mean).toDouble()
                    sqSum += d * d
                }
                val std = sqrt(sqSum / channels.size).toFloat()
                val stdAdj = max(std, 1f / sqrt(channels.size.toDouble()).toFloat())

                for (i in channels.indices) channels[i] = (channels[i] - mean) / stdAdj
            }
        }
    }

    /**
     * Fallback used only when no model file is present: multi-scale grid colour and
     * gradient moments. Much weaker than a learned embedding, but deterministic.
     */
    private fun computeDeterministicFeatureVector(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 112, 112, true)
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val vector = FloatArray(FALLBACK_DIM)
        var idx = 0
        val cellW = w / 8
        val cellH = h / 8

        for (gy in 0 until 8) {
            for (gx in 0 until 8) {
                if (idx >= FALLBACK_DIM) break
                var rSum = 0f
                var gSum = 0f
                var bSum = 0f
                var lumSum = 0f
                var gradSum = 0f
                var count = 0

                for (cy in 0 until cellH) {
                    for (cx in 0 until cellW) {
                        val px = gx * cellW + cx
                        val py = gy * cellH + cy
                        if (px < w && py < h) {
                            val c = pixels[py * w + px]
                            val r = ((c shr 16) and 0xFF).toFloat()
                            val g = ((c shr 8) and 0xFF).toFloat()
                            val b = (c and 0xFF).toFloat()
                            val lum = 0.299f * r + 0.587f * g + 0.114f * b

                            rSum += r
                            gSum += g
                            bSum += b
                            lumSum += lum

                            if (px > 0 && py > 0) {
                                val left = (pixels[py * w + px - 1] and 0xFF).toFloat()
                                val top = (pixels[(py - 1) * w + px] and 0xFF).toFloat()
                                gradSum += Math.abs(lum - left) + Math.abs(lum - top)
                            }
                            count++
                        }
                    }
                }

                if (count > 0) {
                    val n = count.toFloat()
                    if (idx < FALLBACK_DIM) vector[idx++] = rSum / (n * 255f)
                    if (idx < FALLBACK_DIM) vector[idx++] = gSum / (n * 255f)
                    if (idx < FALLBACK_DIM) vector[idx++] = bSum / (n * 255f)
                    if (idx < FALLBACK_DIM) vector[idx++] = lumSum / (n * 255f)
                    if (idx < FALLBACK_DIM) vector[idx++] = gradSum / (n * 255f)
                    if (idx < FALLBACK_DIM) vector[idx++] = (rSum - bSum) / (n * 255f)
                    if (idx < FALLBACK_DIM) vector[idx++] = (gSum - rSum) / (n * 255f)
                    if (idx < FALLBACK_DIM) vector[idx++] = (lumSum / (rSum + 1f))
                }
            }
        }

        scaled.recycle()
        return l2Normalize(vector)
    }

    override fun cosineSimilarity(u: FloatArray, v: FloatArray): Float {
        if (u.isEmpty() || v.isEmpty()) return 0f
        var dot = 0f
        val len = Math.min(u.size, v.size)
        for (i in 0 until len) dot += u[i] * v[i]
        return dot.coerceIn(-1f, 1f)
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0
        for (x in vector) sumSquares += (x * x).toDouble()
        val norm = sqrt(sumSquares).toFloat().coerceAtLeast(1e-8f)
        val result = FloatArray(vector.size)
        for (i in vector.indices) result[i] = vector[i] / norm
        return result
    }
}
