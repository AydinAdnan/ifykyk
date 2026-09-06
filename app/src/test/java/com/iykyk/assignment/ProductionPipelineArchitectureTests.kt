package com.iykyk.assignment

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import com.iykyk.assignment.domain.indexing.ConstrainedCommunityClusterer
import com.iykyk.assignment.domain.indexing.HnswGraphIndex
import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import com.iykyk.assignment.domain.model.SceneTransitionType
import com.iykyk.assignment.domain.model.TransitionKind
import com.iykyk.assignment.domain.pipeline.Tracklet
import com.iykyk.assignment.domain.pipeline.TrackletIdentity
import com.iykyk.assignment.domain.scene.SceneBoundaryDetector
import com.iykyk.assignment.domain.scene.TransitionDetector
import com.iykyk.assignment.domain.tracking.DeepSortLiteTracker
import com.iykyk.assignment.domain.tracking.KalmanBoxTracker
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class ProductionPipelineArchitectureTests {

    private val dummyEmbedder = object : FaceEmbedder {
        override val inputSize: Int = 112
        override fun getEmbedding(faceBitmap: Bitmap?): FloatArray = FloatArray(112) { 0.1f }
        override fun cosineSimilarity(u: FloatArray, v: FloatArray): Float {
            var dot = 0f
            val len = minOf(u.size, v.size)
            for (i in 0 until len) dot += u[i] * v[i]
            return dot.coerceIn(-1f, 1f)
        }
    }

    @Test
    fun testKalmanBoxTracker_predictsSmoothMotionTrajectory() {
        val initialBox = com.iykyk.assignment.domain.tracking.TrackBox(100f, 100f, 200f, 200f)
        val tracker = KalmanBoxTracker(initialBox)

        // Move box to the right by 10px each frame
        for (i in 1..5) {
            val predicted = tracker.predictBox()
            assertNotNull(predicted)
            val measurement = com.iykyk.assignment.domain.tracking.TrackBox(100f + i * 10f, 100f, 200f + i * 10f, 200f)
            tracker.update(measurement)
        }

        // After consistent rightward velocity, next prediction should be ahead of last measurement
        val nextPredicted = tracker.predictBox()
        assertTrue("Kalman should predict forward position, got ${nextPredicted.centerX}", nextPredicted.centerX > 150f)
    }

    @Test
    fun testDeepSortLite_terminatesTracksOnSceneBoundary() {
        val tracker = DeepSortLiteTracker(dummyEmbedder)

        val face1 = DetectedFace(frameIndex = 1, timestampMs = 300L, boundingBox = Rect(10, 10, 100, 100))
        tracker.processFrame(listOf(face1))

        // Scene boundary occurs!
        tracker.onSceneBoundary()

        // After boundary, new face at same position must start a NEW tracklet, never continuing across cut
        val face2 = DetectedFace(frameIndex = 2, timestampMs = 600L, boundingBox = Rect(10, 10, 100, 100))
        tracker.processFrame(listOf(face2))

        val tracklets = tracker.finish()
        assertEquals("Scene boundary must terminate track and yield 2 distinct tracklets", 2, tracklets.size)
    }

    @Test
    fun testHnswGraphIndex_exactRetrievalLogarithmicScale() {
        val hnsw = HnswGraphIndex(dimension = 16)

        val vec1 = l2Norm(FloatArray(16) { if (it == 0) 1f else 0f })
        val vec2 = l2Norm(FloatArray(16) { if (it == 1) 1f else 0f })
        val vec3 = l2Norm(FloatArray(16) { if (it == 2) 1f else 0f })

        hnsw.insert(1, vec1)
        hnsw.insert(2, vec2)
        hnsw.insert(3, vec3)

        val query = l2Norm(FloatArray(16) { if (it == 1) 0.95f else 0.05f })
        val nearest = hnsw.searchKnn(query, k = 1)

        assertEquals("Should retrieve exactly 1 neighbor", 1, nearest.size)
        assertEquals("Top match must be vector 2", 2, nearest.first().first)
        assertTrue("Cosine similarity must be > 0.90", nearest.first().second > 0.90f)
    }

    @Test
    fun testConstrainedCommunityClustering_cannotLinkConstraintRespected() {
        val clusterer = ConstrainedCommunityClusterer(dummyEmbedder, similarityThreshold = 0.40f)

        val vecA = l2Norm(FloatArray(112) { 0.5f })
        // Two tracklets share frameIndex = 10 with non-overlapping boxes (co-occurring distinct people)
        val face1 = DetectedFace(frameIndex = 10, timestampMs = 1000L, boundingBox = Rect(10, 10, 100, 100), embedding = vecA)
        val face2 = DetectedFace(frameIndex = 10, timestampMs = 1000L, boundingBox = Rect(300, 10, 400, 100), embedding = vecA)

        val t1 = Tracklet(1, listOf(face1))
        val t2 = Tracklet(2, listOf(face2))

        val identities = mapOf(
            1 to TrackletIdentity(vecA, listOf(vecA), 1.0f),
            2 to TrackletIdentity(vecA, listOf(vecA), 1.0f)
        )

        val clusters = clusterer.cluster(listOf(t1, t2), identities)
        assertEquals("Co-occurring distinct faces must NEVER be merged, staying 2 unique people", 2, clusters.size)
    }

    @Test
    fun testReIdentification_samePersonReEnteringMaintainsSingleIdentity() {
        val clusterer = ConstrainedCommunityClusterer(dummyEmbedder, similarityThreshold = 0.40f)

        val vecPerson1 = l2Norm(FloatArray(112) { if (it < 10) 1f else 0f })
        val vecPerson2 = l2Norm(FloatArray(112) { if (it in 20..30) 1f else 0f })

        // Person 1 appears at 0s-3s (Tracklet 1)
        val t1 = Tracklet(1, listOf(DetectedFace(frameIndex = 0, timestampMs = 0L, boundingBox = Rect(50, 50, 150, 150), embedding = vecPerson1)))
        // Person 2 appears at 4s-7s (Tracklet 2)
        val t2 = Tracklet(2, listOf(DetectedFace(frameIndex = 10, timestampMs = 4000L, boundingBox = Rect(50, 50, 150, 150), embedding = vecPerson2)))
        // Person 1 returns at 15s-20s after long absence (Tracklet 3)
        val t3 = Tracklet(3, listOf(DetectedFace(frameIndex = 30, timestampMs = 15000L, boundingBox = Rect(50, 50, 150, 150), embedding = vecPerson1)))

        val identities = mapOf(
            1 to TrackletIdentity(vecPerson1, listOf(vecPerson1), 1.0f),
            2 to TrackletIdentity(vecPerson2, listOf(vecPerson2), 1.0f),
            3 to TrackletIdentity(vecPerson1, listOf(vecPerson1), 1.0f)
        )

        val clusters = clusterer.cluster(listOf(t1, t2, t3), identities)
        assertEquals("Tracklet 1 and Tracklet 3 must be unified into Person 1, yielding exactly 2 unique people", 2, clusters.size)
    }

    private fun l2Norm(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in vec) sumSq += (x * x).toDouble()
        val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-8f)
        return FloatArray(vec.size) { vec[it] / norm }
    }
}
