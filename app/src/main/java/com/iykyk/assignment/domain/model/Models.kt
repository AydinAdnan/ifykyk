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
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val alignedCropBitmap: Bitmap? = null,
    val generousCropBitmap: Bitmap? = null,
    val trackingId: Int? = null,
    val isSoloShot: Boolean = true,
    val otherFaceBoxesInFrame: List<Rect> = emptyList(),
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
    /** Native pixel width of the detected face in the source frame. */
    val faceWidthPx: Int get() = boundingBox?.width() ?: 0

    /**
     * True when the whole face box sits inside the frame with a small safety inset,
     * i.e. the face is not clipped by the frame edge.
     */
    val isFullyVisible: Boolean
        get() {
            val box = boundingBox ?: return false
            if (frameWidth <= 0 || frameHeight <= 0) return true
            val insetX = frameWidth * 0.01f
            val insetY = frameHeight * 0.01f
            return box.left >= insetX && box.top >= insetY &&
                box.right <= frameWidth - insetX && box.bottom <= frameHeight - insetY
        }

    /**
     * Google Photos "Top Shot" / "Best Take" scoring algorithm:
     * - Hard penalties for motion blur and closed eyes
     * - Strong preference for solo, unoccluded, frontal, smiling portraits
     */
    val repScore: Float
        get() {
            // Motion blur penalty: blurry faces should never be chosen
            if (sharpnessScore < 50f) return 0.05f

            val frontality = (1f - (Math.abs(headEulerY) / 50f + Math.abs(headEulerZ) / 35f)).coerceIn(0f, 1f)
            val minEyeOpen = Math.min(leftEyeOpenProbability, rightEyeOpenProbability).coerceIn(0f, 1f)
            val eyesOpenScore = if (minEyeOpen < 0.35f) minEyeOpen * 0.2f else minEyeOpen
            val smile = smilingProbability.coerceIn(0f, 1f)
            val sharpness = (sharpnessScore / 350f).coerceIn(0.1f, 1f)
            val soloBonus = if (isSoloShot) 0.35f else 0.0f
            val sizeBonus = ((boundingBox?.width() ?: 100) / 400f).coerceIn(0f, 0.25f)

            return (0.30f * frontality) + (0.25f * sharpness) + (0.20f * eyesOpenScore) + (0.15f * smile) + soloBonus + sizeBonus
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
