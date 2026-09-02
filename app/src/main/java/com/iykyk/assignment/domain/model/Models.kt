package com.iykyk.assignment.domain.model

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect

/**
 * Single detected face in a video frame with extracted attributes and embedding.
 */
data class DetectedFace(
    val frameIndex: Int = 0,
    val timestampMs: Long = 0L,
    val boundingBox: Rect? = null,
    val fullFrameBitmap: Bitmap? = null,
    val alignedCropBitmap: Bitmap? = null,
    val generousCropBitmap: Bitmap? = null,
    val trackingId: Int? = null,
    val leftEye: PointF? = null,
    val rightEye: PointF? = null,
    val nose: PointF? = null,
    val mouthLeft: PointF? = null,
    val mouthRight: PointF? = null,
    val headEulerY: Float = 0f, // Yaw (-90 to +90)
    val headEulerZ: Float = 0f, // Roll (-90 to +90)
    val smilingProbability: Float = 0f,
    val leftEyeOpenProbability: Float = 0f,
    val rightEyeOpenProbability: Float = 0f,
    val sharpnessScore: Float = 0f,
    val embedding: FloatArray = FloatArray(0)
) {
    /**
     * Representative score:
     * w1·frontality + w2·sharpness + w3·eyesOpen + w4·smile + w5·margin
     */
    val repScore: Float
        get() {
            val frontality = (1f - (Math.abs(headEulerY) / 60f + Math.abs(headEulerZ) / 45f)).coerceIn(0f, 1f)
            val eyesOpen = Math.min(leftEyeOpenProbability, rightEyeOpenProbability).coerceIn(0f, 1f)
            val smile = smilingProbability.coerceIn(0f, 1f)
            val sharpness = (sharpnessScore / 500f).coerceIn(0f, 1f)
            return (0.30f * frontality) + (0.25f * sharpness) + (0.20f * eyesOpen) + (0.15f * smile) + 0.10f
        }
}

/**
 * A continuous visible appearance of a person.
 */
data class AppearanceSegment(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val detections: List<DetectedFace>
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
    val bestFace: DetectedFace get() = detections.maxByOrNull { it.repScore } ?: detections.first()
}

/**
 * A unique clustered person across the video.
 */
data class PersonCluster(
    val id: Int,
    val personLabel: String,
    val appearanceCount: Int,
    val appearances: List<AppearanceSegment>,
    val representativeShot: DetectedFace,
    val colorIndex: Int = id % 5
)

/**
 * Overall pipeline processing progress.
 */
enum class PipelineStep(val stepNumber: Int, val title: String) {
    EXTRACT_FRAMES(1, "Extract frames"),
    DETECT_FACES(2, "Detect faces"),
    GENERATE_EMBEDDINGS(3, "Generate embeddings"),
    CLUSTER_PEOPLE(4, "Cluster people"),
    COUNT_APPEARANCES(5, "Count appearances"),
    SELECT_BEST_SHOTS(6, "Select best shots"),
    CREATE_COLLAGE(7, "Create collage")
}

data class PipelineProgress(
    val currentStep: PipelineStep = PipelineStep.EXTRACT_FRAMES,
    val progressPercent: Int = 0,
    val currentFaceBitmap: Bitmap? = null,
    val statusMessage: String = "Initializing video pipeline...",
    val completedSteps: Set<PipelineStep> = emptySet(),
    val isFinished: Boolean = false,
    val error: String? = null,
    val finalResult: AnalysisResult? = null
)

/**
 * Final result ready for UI and export.
 */
data class AnalysisResult(
    val videoUri: String,
    val videoDurationMs: Long,
    val totalUniquePeople: Int,
    val totalAppearances: Int,
    val clusters: List<PersonCluster>,
    val collageBitmap: Bitmap? = null
)
