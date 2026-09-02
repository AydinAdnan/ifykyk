package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
     * Returns the identity vector of each tracklet, keyed by tracklet id. Tracklets whose
     * faces are all unusable map to an empty array.
     */
    suspend fun embedAll(
        tracklets: List<Tracklet>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<Int, FloatArray> {
        val total = plannedInferences(tracklets)
        var done = 0
        val result = HashMap<Int, FloatArray>(tracklets.size)

        for (tracklet in tracklets) {
            val faces = selectFaces(tracklet)
            if (faces.isEmpty()) {
                result[tracklet.id] = FloatArray(0)
                continue
            }

            val dim = embedder.getEmbedding(faces.first().alignedCropBitmap)
            done++
            onProgress(done, total)

            val accumulator = FloatArray(dim.size)
            var weightSum = 0f

            fun accumulate(vector: FloatArray, weight: Float) {
                for (i in 0 until min(accumulator.size, vector.size)) {
                    accumulator[i] += vector[i] * weight
                }
                weightSum += weight
            }
            accumulate(dim, max(0.05f, faces.first().recognitionQuality))

            for (face in faces.drop(1)) {
                accumulate(
                    embedder.getEmbedding(face.alignedCropBitmap),
                    max(0.05f, face.recognitionQuality)
                )
                done++
                onProgress(done, total)
            }

            result[tracklet.id] = if (weightSum > 0f) l2Normalize(accumulator) else FloatArray(0)
        }

        return result
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in vector) sumSq += (x * x).toDouble()
        val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-8f)
        return FloatArray(vector.size) { vector[it] / norm }
    }
}
