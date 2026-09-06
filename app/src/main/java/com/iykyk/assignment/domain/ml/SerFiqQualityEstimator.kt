package com.iykyk.assignment.domain.ml

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * SER-FIQ (Stochastic Embedding Robustness - Face Image Quality) Estimator.
 * Evaluates face quality based on recognition usefulness rather than cosmetic attractiveness.
 *
 * Core Signals:
 * 1. Embedding Stability / Robustness under geometric perturbation
 * 2. Laplacian Variance Sharpness
 * 3. Native Face Resolution in source frame
 * 4. 3D Head Pose Frontality (Euler Yaw, Pitch, Roll)
 * 5. Occlusion & Edge Clipping Factor
 */
class SerFiqQualityEstimator(private val embedder: FaceEmbedder) {

    data class QualityEvaluation(
        /** Composite recognition usefulness score in [0.0, 1.0]. */
        val overallQuality: Float,
        /** Robustness score under perturbation in [0.0, 1.0]. */
        val embeddingStability: Float,
        /** Sharpness factor in [0.0, 1.0]. */
        val sharpnessQuality: Float,
        /** Head pose frontality in [0.0, 1.0]. */
        val frontality: Float,
        /** Resolution sufficiency in [0.0, 1.0]. */
        val resolutionSufficiency: Float,
        /** Occlusion & boundary clearance in [0.0, 1.0]. */
        val boundaryClearance: Float
    )

    fun evaluate(
        alignedFace112: Bitmap?,
        sourceFrame: Bitmap,
        box: Rect,
        landmarks: List<PointF?>,
        eulerX: Float, // Pitch
        eulerY: Float, // Yaw
        eulerZ: Float  // Roll
    ): QualityEvaluation {
        if (alignedFace112 == null || box.width() <= 0 || box.height() <= 0) {
            return QualityEvaluation(0f, 0f, 0f, 0f, 0f, 0f)
        }

        // 1. Embedding Stability: Evaluate embedding similarity between canonical and perturbed crop
        val baseEmbedding = embedder.getEmbedding(alignedFace112)
        val stability = if (baseEmbedding.isNotEmpty()) {
            val perturbed = createPerturbedCrop(alignedFace112)
            val perturbedEmbedding = embedder.getEmbedding(perturbed)
            perturbed.recycle()
            if (perturbedEmbedding.isNotEmpty()) {
                val sim = embedder.cosineSimilarity(baseEmbedding, perturbedEmbedding)
                ((sim - 0.40f) / 0.60f).coerceIn(0f, 1f)
            } else 0.5f
        } else 0.2f

        // 2. Sharpness Quality
        val rawSharpness = FaceAlignmentHelper.computeSharpness(sourceFrame, box)
        val sharpnessQuality = (rawSharpness / (rawSharpness + 150f)).coerceIn(0f, 1f)

        // 3. Head Pose Frontality
        val yawPenalty = abs(eulerY) / 45f
        val pitchPenalty = abs(eulerX) / 35f
        val rollPenalty = abs(eulerZ) / 30f
        val frontality = (1f - (0.50f * yawPenalty + 0.30f * pitchPenalty + 0.20f * rollPenalty)).coerceIn(0f, 1f)

        // 4. Resolution Sufficiency
        val minTargetEdge = 112f
        val resolutionSufficiency = (min(box.width(), box.height()) / minTargetEdge).coerceIn(0f, 1f)

        // 5. Boundary Clearance & Occlusion
        val insetX = sourceFrame.width * 0.015f
        val insetY = sourceFrame.height * 0.015f
        val unclipped = box.left >= insetX && box.top >= insetY &&
            box.right <= sourceFrame.width - insetX && box.bottom <= sourceFrame.height - insetY
        val landmarkCount = landmarks.count { it != null }
        val landmarkRatio = (landmarkCount / max(1f, landmarks.size.toFloat())).coerceIn(0f, 1f)
        val boundaryClearance = (if (unclipped) 0.6f else 0.1f) + 0.4f * landmarkRatio

        // Composite SER-FIQ Utility Score:
        // Heavily weights embedding stability (35%) and sharpness (30%), followed by pose (20%) and size (15%)
        val overallQuality = (
            0.35f * stability +
                0.30f * sharpnessQuality +
                0.20f * frontality +
                0.15f * resolutionSufficiency
            ) * boundaryClearance

        return QualityEvaluation(
            overallQuality = overallQuality.coerceIn(0f, 1f),
            embeddingStability = stability,
            sharpnessQuality = sharpnessQuality,
            frontality = frontality,
            resolutionSufficiency = resolutionSufficiency,
            boundaryClearance = boundaryClearance
        )
    }

    /**
     * Synthesizes a minor stochastic perturbation (slight sub-pixel shift + scaling)
     * to evaluate SER-FIQ embedding variance.
     */
    private fun createPerturbedCrop(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val matrix = android.graphics.Matrix().apply {
            postTranslate(w * 0.03f, h * 0.03f)
            postScale(0.96f, 0.96f, w * 0.5f, h * 0.5f)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return out
    }
}
