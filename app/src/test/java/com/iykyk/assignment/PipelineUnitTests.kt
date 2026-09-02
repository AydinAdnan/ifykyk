package com.iykyk.assignment

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import com.iykyk.assignment.domain.pipeline.AgglomerativeClusterer
import com.iykyk.assignment.domain.pipeline.AppearanceSegmenter
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class PipelineUnitTests {

    private val mockEmbedder = object : FaceEmbedder {
        override fun getEmbedding(faceBitmap112: android.graphics.Bitmap?): FloatArray = FloatArray(512) { 0.1f }
        override fun cosineSimilarity(u: FloatArray, v: FloatArray): Float {
            var dot = 0f
            val len = minOf(u.size, v.size)
            for (i in 0 until len) dot += u[i] * v[i]
            return dot.coerceIn(-1f, 1f)
        }
    }

    @Test
    fun testAgglomerativeClustering_fiveBlobsYieldsFiveClusters() {
        val clusterer = AgglomerativeClusterer(mockEmbedder, similarityThreshold = 0.65f, centroidMergeThreshold = 0.75f)
        val rnd = Random(42)
        val faces = mutableListOf<DetectedFace>()

        // Create 5 distinct orthogonal basis vectors in 512-d space
        val basisIndices = intArrayOf(10, 100, 200, 300, 400)
        val centroids = Array(5) { cIdx ->
            FloatArray(512) { i -> if (i == basisIndices[cIdx]) 1.0f else 0.0f }
        }

        // Generate 4 samples around each centroid (total 20 face instances)
        var faceId = 0
        for (cIdx in 0 until 5) {
            val c = centroids[cIdx]
            for (s in 0 until 4) {
                val sampleVec = FloatArray(512) { i -> c[i] + (rnd.nextGaussian().toFloat() * 0.02f) }
                val normVec = l2Norm(sampleVec)

                faces.add(
                    DetectedFace(
                        frameIndex = faceId,
                        timestampMs = faceId * 500L,
                        embedding = normVec
                    )
                )
                faceId++
            }
        }

        val clusters = clusterer.clusterFaces(faces)
        assertEquals("Should find exactly 5 unique person clusters", 5, clusters.size)
        for ((_, clusterFaces) in clusters) {
            assertEquals("Each person should have 4 face instances", 4, clusterFaces.size)
        }
    }

    @Test
    fun testAppearanceSegmentation_gapsCreateMultipleAppearances() {
        val segmenter = AppearanceSegmenter(maxGapMs = 1200L, minSegmentDurationMs = 350L)
        val faces = listOf(
            // Appearance 1: 0s -> 1.0s (3 frames, gap = 500ms)
            createDummyFace(0L, 1),
            createDummyFace(500L, 1),
            createDummyFace(1000L, 1),
            // Gap of 3000ms -> Appearance 2: 4.0s -> 5.0s
            createDummyFace(4000L, 1),
            createDummyFace(4500L, 1),
            createDummyFace(5000L, 1),
            // Gap of 5000ms -> Appearance 3: 10.0s -> 11.0s
            createDummyFace(10000L, 1),
            createDummyFace(10500L, 1),
            createDummyFace(11000L, 1)
        )

        val personCluster = segmenter.segmentPersonAppearances(1, faces)
        assertEquals("Should segment into exactly 3 continuous appearances", 3, personCluster.appearanceCount)
    }

    @Test
    fun testAppearanceSegmentation_whipPanRejection() {
        val segmenter = AppearanceSegmenter(maxGapMs = 1200L, minSegmentDurationMs = 350L)
        val faces = listOf(
            // Solid appearance 1 (duration = 1000ms)
            createDummyFace(0L, 1),
            createDummyFace(500L, 1),
            createDummyFace(1000L, 1),
            // Whip-pan noise: isolated single frame at 5000ms (duration = 0ms < 350ms)
            createDummyFace(5000L, 1),
            // Solid appearance 2 (duration = 1000ms)
            createDummyFace(10000L, 1),
            createDummyFace(10500L, 1),
            createDummyFace(11000L, 1)
        )

        val personCluster = segmenter.segmentPersonAppearances(1, faces)
        assertEquals("Whip pan noise should be discarded, keeping 2 solid appearances", 2, personCluster.appearanceCount)
    }

    @Test
    fun testRepresentativeShotScoring_frontalSmilingBeatsProfile() {
        val frontalSmiling = createDummyFace(0L, 1).copy(
            headEulerY = 0f,
            headEulerZ = 0f,
            smilingProbability = 0.95f,
            leftEyeOpenProbability = 0.9f,
            rightEyeOpenProbability = 0.9f,
            sharpnessScore = 350f
        )

        val profileBlurry = createDummyFace(500L, 1).copy(
            headEulerY = 45f,
            headEulerZ = 20f,
            smilingProbability = 0.1f,
            leftEyeOpenProbability = 0.2f,
            rightEyeOpenProbability = 0.2f,
            sharpnessScore = 40f
        )

        assertTrue(
            "Frontal smiling sharp face score () must exceed profile blurry face score ()",
            frontalSmiling.repScore > profileBlurry.repScore
        )
    }

    private fun createDummyFace(timestampMs: Long, trackingId: Int): DetectedFace {
        return DetectedFace(
            frameIndex = 0,
            timestampMs = timestampMs,
            trackingId = trackingId,
            embedding = FloatArray(512) { 0.1f }
        )
    }

    private fun l2Norm(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in vec) sumSq += (x * x).toDouble()
        val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-8f)
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }
}
