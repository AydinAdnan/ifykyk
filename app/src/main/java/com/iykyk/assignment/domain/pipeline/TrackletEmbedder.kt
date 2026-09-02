package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Identity of one tracklet.
 *
 * @param centroid quality-weighted mean of [members], L2 normalised
 * @param members the individual face embeddings that produced it. Kept because faces
 *   inside a tracklet are the same person by construction, so the similarities between
 *   them are free samples of the genuine distribution, which ThresholdCalibrator uses to
 *   place the merge threshold.
 * @param confidence how much the faces backing this identity can be trusted, in 0..1
 */
data class TrackletIdentity(
    val centroid: FloatArray,
    val members: List<FloatArray>,
    val confidence: Float
) {
    val isUsable: Boolean get() = centroid.isNotEmpty()
}

/**
 * Computes one identity vector per tracklet.
 *
 * Embedding every detection is the single most expensive thing the pipeline did, and
 * almost all of it was wasted. The bundled model is an Inception-ResNet-V1 FaceNet, which
 * is heavy on mobile, and a typical clip yields a couple of hundred detections - each of
 * which was run through it twice for flip averaging. Yet detections inside one tracklet
 * are the same person by construction, so all those vectors were only ever averaged back
 * into a single centroid.
 *
 * Only the best few faces of each tracklet are embedded now. Since they are chosen by
 * recognition quality rather than taken at random, the centroid is if anything cleaner
 * than the all-frames average was: the blurred and half-turned frames that used to drag it
 * toward the middle of the embedding space are simply never evaluated.
 */
class TrackletEmbedder(
    private val embedder: FaceEmbedder,
    /** How many faces of a tracklet contribute to its identity. */
    private val facesPerTracklet: Int = 3,
    /** Minimum recognitionQuality for a face to be considered. */
    private val minQuality: Float = 0.22f
) {

    /** Faces that will actually be run through the model, best first. */
    fun selectFaces(tracklet: Tracklet): List<DetectedFace> {
        val usable = tracklet.detections
            .filter { it.alignedCropBitmap != null && it.recognitionQuality >= minQuality }
            .ifEmpty { tracklet.detections.filter { it.alignedCropBitmap != null } }

        return usable
            .sortedByDescending { it.recognitionQuality }
            .take(facesPerTracklet)
    }

    /** Number of model invocations [embedAll] will perform, for progress reporting. */
    fun plannedInferences(tracklets: List<Tracklet>): Int =
        tracklets.sumOf { selectFaces(it).size }

    /**
     * Returns the identity of each tracklet, keyed by tracklet id. Tracklets whose faces
     * are all unusable map to an identity with an empty centroid.
     */
    suspend fun embedAll(
        tracklets: List<Tracklet>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<Int, TrackletIdentity> {
        val total = plannedInferences(tracklets)
        var done = 0
        val result = HashMap<Int, TrackletIdentity>(tracklets.size)

        for (tracklet in tracklets) {
            val faces = selectFaces(tracklet)
            if (faces.isEmpty()) {
                result[tracklet.id] = TrackletIdentity(FloatArray(0), emptyList(), 0f)
                continue
            }

            val members = mutableListOf<FloatArray>()
            val weights = mutableListOf<Float>()

            for (face in faces) {
                val vector = embedder.getEmbedding(face.alignedCropBitmap)
                done++
                onProgress(done, total)

                if (vector.isNotEmpty()) {
                    members.add(vector)
                    weights.add(max(0.05f, face.recognitionQuality))
                }
            }

            result[tracklet.id] = buildIdentity(members, weights, faces)
        }

        return result
    }

    private fun buildIdentity(
        members: List<FloatArray>,
        weights: List<Float>,
        faces: List<DetectedFace>
    ): TrackletIdentity {
        if (members.isEmpty()) return TrackletIdentity(FloatArray(0), emptyList(), 0f)

        val accumulator = FloatArray(members.first().size)
        var weightSum = 0f
        for ((i, vector) in members.withIndex()) {
            val weight = weights[i]
            for (k in 0 until min(accumulator.size, vector.size)) {
                accumulator[k] += vector[k] * weight
            }
            weightSum += weight
        }

        val confidence = faces.map { it.recognitionQuality }.average().toFloat().coerceIn(0f, 1f)
        return TrackletIdentity(
            centroid = if (weightSum > 0f) l2Normalize(accumulator) else FloatArray(0),
            members = members,
            confidence = confidence
        )
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in vector) sumSq += (x * x).toDouble()
        val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-8f)
        return FloatArray(vector.size) { vector[it] / norm }
    }
}
