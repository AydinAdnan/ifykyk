package com.iykyk.assignment.domain.tracking

import android.graphics.Rect
import android.graphics.RectF
import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import com.iykyk.assignment.domain.pipeline.Tracklet
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * DeepSORT-Lite Tracker.
 * Fuses deep appearance embeddings with Kalman motion prediction and spatial overlap.
 * Association Score = 50% Embedding Sim + 30% Spatial IoU + 20% Kalman Motion Prediction.
 */
class DeepSortLiteTracker(
    private val embedder: FaceEmbedder,
    private val maxAge: Int = 4, // Max frames a track survives occlusion (~1.2s at 3fps)
    private val minAssociationScore: Float = 0.40f
) {

    class ActiveTrack(
        val id: Int,
        initialFace: DetectedFace
    ) {
        val kalman = KalmanBoxTracker(
            initialFace.boundingBox?.let { TrackBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
                ?: TrackBox(0f, 0f, 100f, 100f)
        )
        val detections = mutableListOf(initialFace)
        val embeddingHistory = mutableListOf<FloatArray>()
        var confirmed = false

        init {
            if (initialFace.embedding.isNotEmpty()) {
                embeddingHistory.add(initialFace.embedding)
            }
        }

        fun update(face: DetectedFace) {
            detections.add(face)
            kalman.update(
                face.boundingBox?.let { TrackBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
                    ?: TrackBox(0f, 0f, 100f, 100f)
            )
            if (face.embedding.isNotEmpty()) {
                embeddingHistory.add(face.embedding)
                if (embeddingHistory.size > 8) {
                    embeddingHistory.removeAt(0)
                }
            }
            if (detections.size >= 2) {
                confirmed = true
            }
        }

        fun getRepresentativeEmbedding(): FloatArray {
            return embeddingHistory.lastOrNull() ?: FloatArray(0)
        }
    }

    private var nextTrackId = 1
    private val activeTracks = mutableListOf<ActiveTrack>()
    private val completedTracklets = mutableListOf<Tracklet>()

    fun reset() {
        activeTracks.clear()
        completedTracklets.clear()
        nextTrackId = 1
    }

    /**
     * Called when a scene boundary or transition is detected to prevent identity contamination across cuts.
     */
    fun onSceneBoundary() {
        for (track in activeTracks) {
            if (track.detections.isNotEmpty()) {
                completedTracklets.add(Tracklet(track.id, track.detections.toList()))
            }
        }
        activeTracks.clear()
    }

    /**
     * Processes detections in the current frame step.
     */
    fun processFrame(faces: List<DetectedFace>): List<Tracklet> {
        // 1. Predict Kalman locations for all active tracks
        for (track in activeTracks) {
            track.kalman.predict()
        }

        val unassignedTracks = activeTracks.indices.toMutableSet()
        val unassignedFaces = faces.indices.toMutableSet()

        // 2. Compute cost / association matrix
        val matches = mutableListOf<Triple<Float, Int, Int>>() // Score, trackIdx, faceIdx
        for (tIdx in activeTracks.indices) {
            val track = activeTracks[tIdx]
            val predictedBox = track.kalman.getCurrentTrackBox()
            val trackEmb = track.getRepresentativeEmbedding()

            for (fIdx in faces.indices) {
                val face = faces[fIdx]
                val faceBox = face.boundingBox?.let {
                    TrackBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
                } ?: TrackBox(0f, 0f, 100f, 100f)

                val iouScore = computeIoU(predictedBox, faceBox)
                val motionScore = computeMotionScore(predictedBox, faceBox)
                val embeddingScore = if (trackEmb.isNotEmpty() && face.embedding.isNotEmpty()) {
                    embedder.cosineSimilarity(trackEmb, face.embedding).coerceIn(0f, 1f)
                } else {
                    0.5f // Neutral fallback if embeddings not yet available
                }

                // 50% Embedding + 30% IoU + 20% Motion Prediction
                val compositeScore = 0.50f * embeddingScore + 0.30f * iouScore + 0.20f * motionScore
                if (compositeScore >= minAssociationScore) {
                    matches.add(Triple(compositeScore, tIdx, fIdx))
                }
            }
        }

        // Greedy matching in descending order of score
        matches.sortByDescending { it.first }
        for ((_, tIdx, fIdx) in matches) {
            if (tIdx in unassignedTracks && fIdx in unassignedFaces) {
                activeTracks[tIdx].update(faces[fIdx])
                unassignedTracks.remove(tIdx)
                unassignedFaces.remove(fIdx)
            }
        }

        // 3. Create new tracks for unassigned detections
        for (fIdx in unassignedFaces) {
            val newTrack = ActiveTrack(nextTrackId++, faces[fIdx])
            activeTracks.add(newTrack)
        }

        // 4. Age tracks and retire expired tracks
        val survivingTracks = mutableListOf<ActiveTrack>()
        for (track in activeTracks) {
            if (track.kalman.timeSinceUpdate <= maxAge) {
                survivingTracks.add(track)
            } else {
                if (track.detections.isNotEmpty()) {
                    completedTracklets.add(Tracklet(track.id, track.detections.toList()))
                }
            }
        }
        activeTracks.clear()
        activeTracks.addAll(survivingTracks)

        return getActiveTracklets()
    }

    /**
     * Finalizes all open tracks at video end and returns complete tracklet list.
     */
    fun finish(): List<Tracklet> {
        for (track in activeTracks) {
            if (track.detections.isNotEmpty()) {
                completedTracklets.add(Tracklet(track.id, track.detections.toList()))
            }
        }
        activeTracks.clear()
        return completedTracklets.sortedBy { it.startMs }
    }

    private fun getActiveTracklets(): List<Tracklet> {
        return activeTracks.map { Tracklet(it.id, it.detections.toList()) }
    }

    private fun computeIoU(a: TrackBox, b: TrackBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = interRight - interLeft
        val interH = interBottom - interTop
        if (interW <= 0f || interH <= 0f) return 0f

        val interArea = interW * interH
        val unionArea = a.area + b.area - interArea
        return if (unionArea > 0f) (interArea / unionArea).coerceIn(0f, 1f) else 0f
    }

    private fun computeMotionScore(predicted: TrackBox, measurement: TrackBox): Float {
        val centerDist = hypot(predicted.centerX - measurement.centerX, predicted.centerY - measurement.centerY)
        val span = max(predicted.width, measurement.width).coerceAtLeast(1f)
        val normalizedDrift = centerDist / span
        return (1f - (normalizedDrift / 1.5f)).coerceIn(0f, 1f)
    }
}
