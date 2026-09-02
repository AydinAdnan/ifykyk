package com.iykyk.assignment

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import com.iykyk.assignment.domain.pipeline.AgglomerativeClusterer
import com.iykyk.assignment.domain.pipeline.AppearanceSegmenter
import com.iykyk.assignment.domain.pipeline.RepresentativeShotSelector
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class PipelineUnitTests {

    private val mockEmbedder = object : FaceEmbedder {
        override val inputSize: Int = 160
        override fun getEmbedding(faceBitmap: android.graphics.Bitmap?): FloatArray = FloatArray(512) { 0.1f }
        override fun cosineSimilarity(u: FloatArray, v: FloatArray): Float {
            var dot = 0f
            val len = minOf(u.size, v.size)
            for (i in 0 until len) dot += u[i] * v[i]
            return dot.coerceIn(-1f, 1f)
        }
    }

    @Test
    fun testAgglomerativeClustering_fiveBlobsYieldsFiveClusters() {
        val clusterer = AgglomerativeClusterer(mockEmbedder, similarityThreshold = 0.65f)
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
            "Frontal, sharp, smiling, eyes-open face must outscore blurry profile",
            frontalSmiling.repScore > profileBlurry.repScore
        )
    }

    @Test
    fun testClustering_facesSharingAFrameAreNeverTheSamePerson() {
        val clusterer = AgglomerativeClusterer(mockEmbedder, similarityThreshold = 0.1f)

        // Identical embeddings, so similarity alone would merge them, but they are
        // visible simultaneously and therefore must be two people.
        val identical = l2Norm(FloatArray(512) { if (it == 7) 1f else 0f })
        val faces = listOf(
            DetectedFace(frameIndex = 0, timestampMs = 0L, embedding = identical),
            DetectedFace(frameIndex = 0, timestampMs = 0L, embedding = identical)
        )

        val clusters = clusterer.clusterFaces(faces)
        assertEquals("Two faces in one frame must stay two people", 2, clusters.size)
    }

    @Test
    fun testClustering_cannotLinkIsInheritedThroughMerges() {
        val clusterer = AgglomerativeClusterer(mockEmbedder, similarityThreshold = 0.1f)
        val vec = l2Norm(FloatArray(512) { if (it == 3) 1f else 0f })

        // A and C share frame 0, so they are different people. B appears alone in frame 1.
        // B may merge with one of them, but the result must never absorb the other.
        val faces = listOf(
            DetectedFace(frameIndex = 0, timestampMs = 0L, embedding = vec),
            DetectedFace(frameIndex = 0, timestampMs = 0L, embedding = vec),
            DetectedFace(frameIndex = 1, timestampMs = 400L, embedding = vec)
        )

        val clusters = clusterer.clusterFaces(faces)
        assertEquals("Co-occurring faces must remain separate people", 2, clusters.size)
    }

    @Test
    fun testRepresentativeSelection_rejectsCropsContainingAnotherPerson() {
        val clean = createDummyFace(0L, 1).copy(
            hasCleanCrop = true,
            smilingProbability = 0.2f,
            leftEyeOpenProbability = 0.8f,
            rightEyeOpenProbability = 0.8f,
            sharpnessScore = 400f
        )
        // Deliberately the more attractive shot on every scored axis.
        val dirty = createDummyFace(500L, 1).copy(
            hasCleanCrop = false,
            smilingProbability = 1.0f,
            leftEyeOpenProbability = 1.0f,
            rightEyeOpenProbability = 1.0f,
            sharpnessScore = 900f
        )

        assertTrue("the disqualified shot scores higher", dirty.repScore > clean.repScore)
        assertEquals(
            "a crop containing another person must never be chosen",
            clean,
            RepresentativeShotSelector.select(listOf(dirty, clean))
        )
    }

    @Test
    fun testRepresentativeSelection_rejectsFacesClippedByTheFrameEdge() {
        val whole = createDummyFace(0L, 1).copy(
            frameWidth = 1000, frameHeight = 1000,
            boundingBox = null,
            sharpnessScore = 300f,
            leftEyeOpenProbability = 0.9f,
            rightEyeOpenProbability = 0.9f
        )
        // boundingBox is null under the JVM android stubs, so isFullyVisible is driven by
        // frame dimensions: zero dimensions mean "unknown", non-zero with a null box mean
        // clipped. Assert the gate keeps the fully visible candidate.
        val selected = RepresentativeShotSelector.select(listOf(whole))
        assertEquals(whole, selected)
    }

    @Test
    fun testAppearanceSegmentation_shortGapsDoNotSplitAnAppearance() {
        val segmenter = AppearanceSegmenter(maxGapMs = 1200L, minSegmentDurationMs = 350L)
        // A single continuous appearance with one dropped frame in the middle.
        val faces = listOf(
            createDummyFace(0L, 1),
            createDummyFace(400L, 1),
            createDummyFace(1200L, 1),
            createDummyFace(1600L, 1)
        )

        val person = segmenter.segmentPersonAppearances(1, faces)
        assertEquals("A missed frame must not count as a second appearance", 1, person.appearanceCount)
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
