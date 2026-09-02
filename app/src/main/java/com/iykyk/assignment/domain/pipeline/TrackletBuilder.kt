package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import kotlin.math.hypot
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
    private val minIou: Float = 0.3f,
    /**
     * Alternative to overlap for fast motion: centres closer together than this multiple
     * of the face width are the same track even when the boxes no longer intersect.
     */
    private val maxCentreDrift: Float = 1.1f,
    /** Frames may be skipped this many times before a track is considered ended. */
    private val maxFrameGap: Int = 2,
    /** A geometric match is rejected if the faces look this dissimilar. */
    private val minAppearanceSimilarity: Float = 0.25f,
    /** Smallest ratio between two face widths that can still be one track. */
    private val minScaleRatio: Float = 0.55f
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

        // Faces at very different scales are at very different depths, so they are not
        // the same track however much their boxes happen to overlap. This carries weight
        // now that tracking runs before embedding and cannot fall back to appearance.
        val scaleRatio = min(a.width(), b.width()).toFloat() / max(a.width(), b.width()).coerceAtLeast(1)
        if (scaleRatio < minScaleRatio) return 0f

        val iou = iou(a.left, a.top, a.right, a.bottom, b.left, b.top, b.right, b.bottom)
        val sameTrackingId = previous.trackingId != null &&
            previous.trackingId == candidate.trackingId

        // Overlap alone is too brittle on handheld footage. A third of a second of camera
        // shake can move a face further than its own width, dropping IoU to zero between
        // consecutive samples even though nothing else changed, which shatters one person
        // into a string of single-frame tracks with unreliable identities. Proximity
        // relative to face size survives that; the scale gate above keeps it honest.
        val centreDistance = hypot(a.exactCenterX() - b.exactCenterX(), a.exactCenterY() - b.exactCenterY())
        val faceSpan = max(a.width(), b.width()).toFloat().coerceAtLeast(1f)
        val drift = centreDistance / faceSpan
        val nearby = drift <= maxCentreDrift

        if (iou < minIou && !nearby && !(sameTrackingId && iou > 0.1f)) return 0f

        val appearance = if (previous.embedding.isNotEmpty() && candidate.embedding.isNotEmpty()) {
            embedder.cosineSimilarity(previous.embedding, candidate.embedding)
        } else {
            1f
        }
        if (appearance < minAppearanceSimilarity) return 0f

        val proximity = (1f - (drift / maxCentreDrift)).coerceIn(0f, 1f)
        return iou + proximity * 0.4f + (if (sameTrackingId) 0.5f else 0f) + appearance * 0.25f
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
