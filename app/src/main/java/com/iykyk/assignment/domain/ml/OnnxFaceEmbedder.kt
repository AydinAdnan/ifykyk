package com.iykyk.assignment.domain.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.iykyk.assignment.domain.model.PersonEmbeddingProfile
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Production-grade InsightFace Buffalo_SC / MobileFaceNet Embedder using ONNX Runtime Mobile.
 * Configured with hardware XNNPACK delegates for low-latency CPU inference.
 * Replaces simple centroid averaging with multi-vector [PersonEmbeddingProfile] fusion.
 */
class OnnxFaceEmbedder(private val context: Context? = null) : FaceEmbedder {

    companion object {
        private const val TAG = "OnnxFaceEmbedder"
        const val INPUT_SIZE = 112
        const val EMBEDDING_DIM = 512
    }

    override val inputSize: Int = INPUT_SIZE

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputTensorName: String = "input"
    private var isUsingOnnx: Boolean = false

    // Backup TFLite embedder if onnx model file is absent
    private val tfliteFallback by lazy { TFLiteFaceEmbedder(context) }

    init {
        initializeOnnxRuntime()
    }

    private fun initializeOnnxRuntime() {
        if (context == null) return
        try {
            val assetManager = context.assets
            val assetList = assetManager.list("") ?: emptyArray()
            val onnxFileName = assetList.firstOrNull { it.endsWith(".onnx", ignoreCase = true) }

            if (onnxFileName != null) {
                // Copy asset to cache file for ONNX session
                val cacheFile = File(context.cacheDir, onnxFileName)
                if (!cacheFile.exists() || cacheFile.length() == 0L) {
                    assetManager.open(onnxFileName).use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val env = OrtEnvironment.getEnvironment()
                ortEnv = env

                val opts = OrtSession.SessionOptions().apply {
                    try {
                        addXnnpack(mapOf("intra_op_num_threads" to "4"))
                    } catch (e: Throwable) {
                        Log.w(TAG, "XNNPACK delegate not available, using default threads", e)
                    }
                    setIntraOpNumThreads(4)
                    setInterOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }

                val session = env.createSession(cacheFile.absolutePath, opts)
                ortSession = session
                inputTensorName = session.inputNames.iterator().next()
                isUsingOnnx = true
                Log.i(TAG, "ONNX Runtime Mobile initialized with model: $onnxFileName, XNNPACK enabled")
            } else {
                Log.i(TAG, "No .onnx model found in assets. Falling back to native TFLite pipeline.")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize ONNX Runtime, using native fallback", e)
            isUsingOnnx = false
        }
    }

    override fun getEmbedding(faceBitmap: Bitmap?): FloatArray {
        if (faceBitmap == null) return FloatArray(0)

        if (isUsingOnnx && ortSession != null && ortEnv != null) {
            val direct = runOnnxInference(faceBitmap, mirrored = false)
            if (direct.isNotEmpty()) {
                val flipped = runOnnxInference(faceBitmap, mirrored = true)
                return if (flipped.isNotEmpty()) {
                    val fused = FloatArray(direct.size) { direct[it] + flipped[it] }
                    l2Normalize(fused)
                } else {
                    direct
                }
            }
        }

        // Seamless fallback to TFLite model
        return tfliteFallback.getEmbedding(faceBitmap)
    }

    private fun runOnnxInference(bitmap: Bitmap, mirrored: Boolean): FloatArray {
        val env = ortEnv ?: return FloatArray(0)
        val session = ortSession ?: return FloatArray(0)

        return try {
            val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            }

            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            if (scaled !== bitmap) scaled.recycle()

            // Buffalo_SC input: NCHW format [1, 3, 112, 112], float32 normalized (x - 127.5) / 127.5
            val floatBuffer = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
            val channelSize = INPUT_SIZE * INPUT_SIZE

            // R channel
            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    val srcX = if (mirrored) INPUT_SIZE - 1 - x else x
                    val pixel = pixels[y * INPUT_SIZE + srcX]
                    val r = ((pixel shr 16) and 0xFF).toFloat()
                    floatBuffer.put(((r - 127.5f) / 127.5f))
                }
            }
            // G channel
            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    val srcX = if (mirrored) INPUT_SIZE - 1 - x else x
                    val pixel = pixels[y * INPUT_SIZE + srcX]
                    val g = ((pixel shr 8) and 0xFF).toFloat()
                    floatBuffer.put(((g - 127.5f) / 127.5f))
                }
            }
            // B channel
            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    val srcX = if (mirrored) INPUT_SIZE - 1 - x else x
                    val pixel = pixels[y * INPUT_SIZE + srcX]
                    val b = (pixel and 0xFF).toFloat()
                    floatBuffer.put(((b - 127.5f) / 127.5f))
                }
            }
            floatBuffer.rewind()

            val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val tensor = OnnxTensor.createTensor(env, floatBuffer, shape)

            val output = session.run(mapOf(inputTensorName to tensor))
            val resultValue = output[0].value
            tensor.close()
            output.close()

            val rawVec = when (resultValue) {
                is Array<*> -> (resultValue[0] as FloatArray)
                is FloatArray -> resultValue
                else -> FloatArray(0)
            }

            l2Normalize(rawVec)
        } catch (e: Throwable) {
            Log.e(TAG, "ONNX inference error", e)
            FloatArray(0)
        }
    }

    override fun cosineSimilarity(u: FloatArray, v: FloatArray): Float {
        if (u.isEmpty() || v.isEmpty() || u.size != v.size) return 0f
        var dot = 0f
        for (i in u.indices) dot += u[i] * v[i]
        return dot.coerceIn(-1f, 1f)
    }

    /**
     * Builds a comprehensive [PersonEmbeddingProfile] using quality-weighted multi-vector fusion.
     */
    fun buildEmbeddingProfile(
        personId: Int,
        embeddings: List<FloatArray>,
        qualities: List<Float>
    ): PersonEmbeddingProfile {
        require(embeddings.isNotEmpty()) { "Cannot build profile from empty embeddings" }
        val dim = embeddings.first().size
        val count = embeddings.size

        // 1. Best embedding (highest SER-FIQ quality)
        var bestIdx = 0
        var bestQ = -1f
        for (i in qualities.indices) {
            if (qualities[i] > bestQ) {
                bestQ = qualities[i]
                bestIdx = i
            }
        }
        val bestEmbedding = embeddings[bestIdx]

        // 2. Average embedding
        val avgAccum = FloatArray(dim)
        for (vec in embeddings) {
            for (d in 0 until dim) {
                avgAccum[d] += vec[d]
            }
        }
        for (d in 0 until dim) avgAccum[d] /= count.toFloat()
        val averageEmbedding = l2Normalize(avgAccum)

        // 3. Quality-weighted embedding
        val qwAccum = FloatArray(dim)
        var qSum = 0f
        for (i in embeddings.indices) {
            val q = max(0.05f, qualities.getOrElse(i) { 1.0f })
            val vec = embeddings[i]
            for (d in 0 until dim) {
                qwAccum[d] += vec[d] * q
            }
            qSum += q
        }
        val qualityWeightedEmbedding = if (qSum > 0f) l2Normalize(qwAccum) else averageEmbedding

        // 4. Component-wise variance
        val variance = FloatArray(dim)
        for (vec in embeddings) {
            for (d in 0 until dim) {
                val diff = vec[d] - averageEmbedding[d]
                variance[d] += diff * diff
            }
        }
        for (d in 0 until dim) variance[d] /= max(1, count).toFloat()

        val meanQuality = if (qualities.isNotEmpty()) qualities.average().toFloat() else 1.0f

        return PersonEmbeddingProfile(
            personId = personId,
            bestEmbedding = bestEmbedding,
            averageEmbedding = averageEmbedding,
            qualityWeightedEmbedding = qualityWeightedEmbedding,
            embeddingVariance = variance,
            memberCount = count,
            meanQuality = meanQuality
        )
    }

    /**
     * Multi-vector profile matching: combines best take similarity with quality-weighted centroid.
     */
    fun profileSimilarity(p1: PersonEmbeddingProfile, p2: PersonEmbeddingProfile): Float {
        val simQw = cosineSimilarity(p1.qualityWeightedEmbedding, p2.qualityWeightedEmbedding)
        val simBest = cosineSimilarity(p1.bestEmbedding, p2.bestEmbedding)
        val simCross1 = cosineSimilarity(p1.bestEmbedding, p2.qualityWeightedEmbedding)
        val simCross2 = cosineSimilarity(p2.bestEmbedding, p1.qualityWeightedEmbedding)

        // Balanced fusion: 60% quality-weighted centroid + 40% best-take match
        return (0.60f * simQw + 0.20f * simBest + 0.10f * simCross1 + 0.10f * simCross2).coerceIn(-1f, 1f)
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in vector) sumSq += (x * x).toDouble()
        val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-8f)
        return FloatArray(vector.size) { vector[it] / norm }
    }
}
