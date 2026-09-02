package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One continuous run of detections believed to be the same person, built from spatial
 * continuity between neighbouring sampled frames.
 */
data class Tracklet(
    val id: Int,
    val detections: List<DetectedFace>
) {
    val startMs: Long get() = detections.first().timestampMs
    val endMs: Long get() = detections.last().timestampMs
    val durationMs: Long get() = endMs - startMs
    val frameIndices: Set<Int> get() = detections.map { it.frameIndex }.toSet()

    /** The detections good enough to contribute to the identity of this tracklet. */
    fun usableDetections(minQuality: Float): List<DetectedFace> =
        detections.filter { it.embedding.isNotEmpty() && it.recognitionQuality >= minQuality }
}

/**
 * Groups per-frame detections into tracklets.
 *
 * Clustering individual detections is unnecessarily hard: every blurry, half-turned frame
 * becomes its own decision, and one bad embedding can split a person in two or pull two
 * people together. Faces that are demonstrably the same person - because they sit in
 * nearly the same place in consecutive sampled frames - should be settled by geometry
 * first, so that recognition only has to answer the much easier question of which tracks
 * belong together.
 *
 * ML Kit tracking IDs are used as a hint, but not trusted on their own: tracking is meant
 * for a contiguous camera stream, and at a few frames per second it drops and reissues IDs
 * across ordinary motion.
 */
class TrackletBuilder(
    private val embedder: FaceEmbedder,
    /** Overlap between boxes in adjacent frames above which they are the same track. */
    private val minIou: Float = 0.35f,
    /** Frames may be skipped this many times before a track is considered ended. */
    private val maxFrameGap: Int = 2,
    /** A geometric match is rejected if the faces look this dissimilar. */
    private val minAppearanceSimilarity: Float = 0.25f
) {

    fun build(faces: List<DetectedFace>): List<Tracklet> {
        if (faces.isEmpty()) return emptyList()

        val byFrame = faces.groupBy { it.frameIndex }.toSortedMap()
        val openTracks = mutableListOf<MutableList<DetectedFace>>()
        val closedTracks = mutableListOf<MutableList<DetectedFace>>()

        for ((frameIndex, framesFaces) in byFrame) {
            // Retire tracks that have not been seen for a while.
            val stillOpen = mutableListOf<MutableList<DetectedFace>>()
            for (track in openTracks) {
                if (frameIndex - track.last().frameIndex > maxFrameGap) {
                    closedTracks.add(track)
                } else {
                    stillOpen.add(track)
                }
            }
            openTracks.clear()
            openTracks.addAll(stillOpen)

            // Greedy best-first assignment: strongest match wins, and each track and each
            // detection is used at most once per frame, so two people standing close
            // together cannot both be absorbed into one track.
            val candidates = mutableListOf<Triple<Float, Int, DetectedFace>>()
            for ((trackIdx, track) in openTracks.withIndex()) {
                for (face in framesFaces) {
                    val score = matchScore(track.last(), face)
                    if (score > 0f) candidates.add(Triple(score, trackIdx, face))
                }
            }
            candidates.sortByDescending { it.first }

            val usedTracks = mutableSetOf<Int>()
            val usedFaces = mutableSetOf<DetectedFace>()
            for ((_, trackIdx, face) in candidates) {
                if (trackIdx in usedTracks || face in usedFaces) continue
                openTracks[trackIdx].add(face)
                usedTracks.add(trackIdx)
                usedFaces.add(face)
            }

            for (face in framesFaces) {
                if (face !in usedFaces) openTracks.add(mutableListOf(face))
            }
        }

        closedTracks.addAll(openTracks)

        return closedTracks
            .filter { it.isNotEmpty() }
            .sortedBy { it.first().timestampMs }
            .mapIndexed { index, detections ->
                Tracklet(index, detections.sortedBy { it.timestampMs })
            }
    }

    /**
     * Similarity between the last face of a track and a candidate in a later frame.
     * Returns 0 when they cannot be the same track.
     */
    private fun matchScore(previous: DetectedFace, candidate: DetectedFace): Float {
        val a = previous.boundingBox ?: return 0f
        val b = candidate.boundingBox ?: return 0f

        val iou = iou(a.left, a.top, a.right, a.bottom, b.left, b.top, b.right, b.bottom)
        val sameTrackingId = previous.trackingId != null &&
            previous.trackingId == candidate.trackingId

        // A reissued or absent tracking ID must not break a track that geometry supports,
        // and a stale reused ID must not join boxes that are nowhere near each other.
        if (iou < minIou && !(sameTrackingId && iou > 0.1f)) return 0f

        val appearance = if (previous.embedding.isNotEmpty() && candidate.embedding.isNotEmpty()) {
            embedder.cosineSimilarity(previous.embedding, candidate.embedding)
        } else {
            1f
        }
        if (appearance < minAppearanceSimilarity) return 0f

        return iou + (if (sameTrackingId) 0.5f else 0f) + appearance * 0.25f
    }

    private fun iou(
        aLeft: Int, aTop: Int, aRight: Int, aBottom: Int,
        bLeft: Int, bTop: Int, bRight: Int, bBottom: Int
    ): Float {
        val interW = min(aRight, bRight) - max(aLeft, bLeft)
        val interH = min(aBottom, bBottom) - max(aTop, bTop)
        if (interW <= 0 || interH <= 0) return 0f

        val inter = interW.toFloat() * interH
        val areaA = (aRight - aLeft).toFloat() * (aBottom - aTop)
        val areaB = (bRight - bLeft).toFloat() * (bBottom - bTop)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/**
 * Quality-weighted mean of a tracklet embeddings, L2 normalised.
 *
 * Weighting by recognitionQuality matters: a track usually contains a couple of sharp
 * frontal frames and a tail of motion-blurred profile ones, and an unweighted mean lets
 * the poor frames drag the identity toward the middle of the embedding space, where
 * everyone looks alike.
 */
fun trackletEmbedding(tracklet: Tracklet, minQuality: Float): FloatArray {
    val usable = tracklet.usableDetections(minQuality).ifEmpty {
        tracklet.detections.filter { it.embedding.isNotEmpty() }
    }
    if (usable.isEmpty()) return FloatArray(0)

    val dim = usable.first().embedding.size
    val sum = FloatArray(dim)
    var weightSum = 0f

    for (face in usable) {
        val weight = max(0.05f, face.recognitionQuality)
        val emb = face.embedding
        for (i in 0 until min(dim, emb.size)) sum[i] += emb[i] * weight
        weightSum += weight
    }
    if (weightSum <= 0f) return FloatArray(0)

    var sumSq = 0.0
    for (i in sum.indices) {
        sum[i] /= weightSum
        sumSq += (sum[i] * sum[i]).toDouble()
    }
    val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-8f)
    for (i in sum.indices) sum[i] /= norm

    return sum
}
