package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.model.DetectedFace

/**
 * Picks the one shot that will represent a person in the collage.
 *
 * Selection is two-stage on purpose. Disqualifying properties - another person inside the
 * crop, a face clipped by the frame edge, a face too small to render, a badly
 * motion-blurred face - are applied as gates, not as score penalties. A weighted score
 * lets a shot that is disqualifying on one axis win by being excellent on the others,
 * which is exactly how a tile with half a stranger in it, or a blurred face, gets chosen
 * because it happened to be smiling.
 *
 * Gates are applied in descending order of importance and each one is skipped if it would
 * leave nothing, so a person who only ever appears in a crowd still gets their best
 * available shot rather than none.
 */
object RepresentativeShotSelector {

    /**
     * Sharpness below this fraction of the person's best is treated as motion blur.
     *
     * Raised from 0.45: at that level a clearly softer frame still cleared the gate and
     * could then win on the scored axes, because repScore mixes sharpness with size, smile
     * and a solo bonus that together outweigh it.
     */
    private const val RELATIVE_SHARPNESS_FLOOR = 0.7f

    /** Faces narrower than this fraction of the frame width render poorly as a tile. */
    private const val MIN_RELATIVE_WIDTH = 0.07f

    /** Eye-open probability below which a shot counts as blinking. */
    private const val EYES_OPEN_FLOOR = 0.4f

    fun select(candidates: List<DetectedFace>): DetectedFace {
        require(candidates.isNotEmpty()) { "Cannot select a representative from no candidates" }

        val withCrop = candidates.filter { it.generousCropBitmap != null }.orAll(candidates)

        // 1. Rejection filters: drop heavily blurred, profile, crowded, and edge-clipped faces
        val unclipped = withCrop.filter { it.isFullyVisible }.orAll(withCrop)
        val nonCrowded = unclipped.filter { it.hasCleanCrop }.orAll(unclipped)
        val nonProfile = nonCrowded.filter { kotlin.math.abs(it.headEulerY) <= 35f && kotlin.math.abs(it.headEulerZ) <= 30f }.orAll(nonCrowded)

        // 2. Strict Priority 1: Solo Shot Gate (Only solo portraits feature in final collage)
        var pool = nonProfile.filter { it.isSoloShot && it.otherFaceBoxesInFrame.isEmpty() }.orAll(nonProfile)

        // 3. Priority 2: Quality floor
        val bestQuality = pool.maxOfOrNull { it.recognitionQuality } ?: 1.0f
        pool = pool.filter { it.recognitionQuality >= bestQuality * 0.60f }.orAll(pool)

        // 4. Priority 4: Sharpness Floor (Reject motion blurred frames)
        val bestSharpness = pool.maxOfOrNull { it.sharpnessScore } ?: 0f
        pool = pool.filter { it.sharpnessScore >= bestSharpness * RELATIVE_SHARPNESS_FLOOR }.orAll(pool)

        // 5. Open eyes
        pool = pool.filter { it.eyesOpen >= EYES_OPEN_FLOOR }.orAll(pool)

        // Final Selection: Multi-priority ranking (SER-FIQ Quality -> Frontality -> Sharpness -> Face Size)
        return pool.maxWithOrNull(
            compareBy<DetectedFace> { it.isSoloShot && it.otherFaceBoxesInFrame.isEmpty() }
                .thenBy { it.recognitionQuality }
                .thenBy { it.frontality }
                .thenBy { it.sharpnessScore }
                .thenBy { it.faceWidthPx }
        ) ?: pool.first()
    }

    /**
     * Ranks all candidates according to the strict 5-tier priority hierarchy:
     * Priority 1: Solo shot
     * Priority 2: Highest SER-FIQ quality
     * Priority 3: Frontal pose
     * Priority 4: Sharpness
     * Priority 5: Largest face crop
     */
    fun rank(candidates: List<DetectedFace>): List<DetectedFace> {
        if (candidates.isEmpty()) return emptyList()

        return candidates.sortedWith(
            compareByDescending<DetectedFace> { it.generousCropBitmap != null }
                .thenByDescending { it.isSoloShot && it.otherFaceBoxesInFrame.isEmpty() }
                .thenByDescending { it.hasCleanCrop }
                .thenByDescending { it.isFullyVisible }
                .thenByDescending { it.recognitionQuality }
                .thenByDescending { it.frontality }
                .thenByDescending { it.sharpnessScore }
                .thenByDescending { it.faceWidthPx }
        )
    }

    /** Keeps a filter's result, or falls back to [fallback] when the filter emptied it. */
    private fun List<DetectedFace>.orAll(fallback: List<DetectedFace>): List<DetectedFace> =
        ifEmpty { fallback }
}
