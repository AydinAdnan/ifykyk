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
    /** True when the portrait crop for this face excludes every other face in the frame. */
    val hasCleanCrop: Boolean = true,
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
     * Blur score mapped into 0..1 so it can be mixed with the probability-valued
     * attributes. sharpnessScore itself is an unbounded variance of Laplacian.
     */
    val sharpnessQuality: Float
        get() = sharpnessScore / (sharpnessScore + SHARPNESS_MIDPOINT)

    /** How front-facing the head is, from the yaw and roll Euler angles. */
    val frontality: Float
        get() = (1f - (Math.abs(headEulerY) / 50f + Math.abs(headEulerZ) / 35f)).coerceIn(0f, 1f)

    /** Probability that the less-open of the two eyes is open. */
    val eyesOpen: Float
        get() = Math.min(leftEyeOpenProbability, rightEyeOpenProbability).coerceIn(0f, 1f)

    /**
     * Confidence that this crop is a usable face for recognition, independent of how
     * pretty it is. Used to weight embeddings and to drop junk before clustering.
     */
    val recognitionQuality: Float
        get() {
            val sizeTerm = (faceWidthPx / 160f).coerceIn(0f, 1f)
            return (0.45f * sharpnessQuality + 0.35f * frontality + 0.20f * sizeTerm)
                .coerceIn(0f, 1f)
        }

    /**
     * "Top Shot" style score for choosing which frame represents a person:
     * favours frontal, crisp, eyes-open, pleasant, unclipped, solo portraits.
     *
     * Hard disqualifiers (clipped face, neighbours in the crop, too small) are applied as
     * gates before scoring rather than as penalties here, so that a shot that fails them
     * can never win merely by scoring well elsewhere.
     */
    val repScore: Float
        get() {
            val eyesTerm = if (eyesOpen < 0.35f) eyesOpen * 0.2f else eyesOpen
            val smile = smilingProbability.coerceIn(0f, 1f)
            val sizeTerm = (faceWidthPx / 320f).coerceIn(0f, 1f)
            val soloBonus = if (isSoloShot) 0.08f else 0f

            // Sharpness carries the most weight. Previously the size term and the solo
            // bonus together outweighed it, so a large blurred face beat a crisp smaller
            // one - and blur is the flaw a viewer notices first in a finished collage.
            return (0.38f * sharpnessQuality) +
                (0.24f * frontality) +
                (0.18f * eyesTerm) +
                (0.10f * smile) +
                (0.08f * sizeTerm) +
                soloBonus
        }

    companion object {
        /**
         * Laplacian variance at which sharpnessQuality reaches 0.5. Calibrated for the
         * 128x128 luminance patch used by FaceAlignmentHelper.computeSharpness.
         */
        const val SHARPNESS_MIDPOINT = 150f
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
