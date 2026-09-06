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
        return rank(candidates).first()
    }

    fun isSoloPortrait(f: DetectedFace): Boolean {
        if (!f.isSoloShot || f.otherFaceBoxesInFrame.isNotEmpty()) return false
        val box = f.boundingBox ?: return true
        if (f.frameWidth <= 0 || f.frameHeight <= 0) return true
        val w = f.frameWidth.toFloat()
        val h = f.frameHeight.toFloat()
        val cx = (box.left + box.right) / 2f

        // In vertical mobile video (h > w), split-screen / duet / two-shot frames place subjects
        // in the left half (cx < 0.36w, right <= 0.50w) or right half (cx > 0.64w, left >= 0.50w).
        // A genuine solo portrait is centered in the frame and straddles the vertical centerline.
        return if (h > w) {
            val isCentered = cx >= w * 0.36f && cx <= w * 0.64f
            val straddlesCenterline = box.left < w * 0.49f && box.right > w * 0.51f
            isCentered && straddlesCenterline
        } else {
            cx >= w * 0.25f && cx <= w * 0.75f
        }
    }

    /**
     * Ranks all candidates according to the strict priority hierarchy:
     * Priority 1: Centered solo portraits only (multi-person group scenes & split-screens are filtered out)
     * Priority 2: Clean crop (no overlapping neighbours)
     * Priority 3: Fully visible / unclipped
     * Priority 4: Highest SER-FIQ quality
     * Priority 5: Frontal pose & high sharpness
     * Priority 6: Largest face crop
     */
    fun rank(candidates: List<DetectedFace>): List<DetectedFace> {
        if (candidates.isEmpty()) return emptyList()

        val withCrop = candidates.filter { it.generousCropBitmap != null }.ifEmpty { candidates }

        // Strict priority 1: Solitary centered portrait shots (zero other people, no split screens)
        val soloShots = withCrop.filter { isSoloPortrait(it) }
        val pool = if (soloShots.isNotEmpty()) soloShots else withCrop

        return pool.sortedWith(
            compareByDescending<DetectedFace> { isSoloPortrait(it) }
                .thenByDescending { it.hasCleanCrop }
                .thenByDescending { it.isFullyVisible }
                .thenByDescending { it.sharpnessScore >= 40f }
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
