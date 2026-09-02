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

    /** Sharpness below this fraction of the person's best is treated as motion blur. */
    private const val RELATIVE_SHARPNESS_FLOOR = 0.45f

    /** Faces narrower than this fraction of the frame width render poorly as a tile. */
    private const val MIN_RELATIVE_WIDTH = 0.07f

    /** Eye-open probability below which a shot counts as blinking. */
    private const val EYES_OPEN_FLOOR = 0.4f

    fun select(candidates: List<DetectedFace>): DetectedFace {
        require(candidates.isNotEmpty()) { "Cannot select a representative from no candidates" }

        val withCrop = candidates.filter { it.generousCropBitmap != null }.orAll(candidates)

        // 1. No other person may appear in the tile.
        var pool = withCrop.filter { it.hasCleanCrop }.orAll(withCrop)

        // 2. The whole face must be in frame; a face sliced by the frame edge cannot be
        //    framed generously no matter how good the crop planner is.
        pool = pool.filter { it.isFullyVisible }.orAll(pool)

        // 3. Enough pixels to survive being drawn at collage size.
        pool = pool.filter { face ->
            face.frameWidth > 0 && face.faceWidthPx >= face.frameWidth * MIN_RELATIVE_WIDTH
        }.orAll(pool)

        // 4. Sharpness is judged relative to what this person's footage actually offers.
        //    An absolute threshold either rejects everyone in a soft-lit clip or nobody in
        //    a crisp one, because variance of Laplacian scales with scene contrast.
        val bestSharpness = pool.maxOf { it.sharpnessScore }
        pool = pool.filter { it.sharpnessScore >= bestSharpness * RELATIVE_SHARPNESS_FLOOR }
            .orAll(pool)

        // 5. Prefer open eyes, but never at the cost of the gates above.
        pool = pool.filter { it.eyesOpen >= EYES_OPEN_FLOOR }.orAll(pool)

        return pool.maxByOrNull { it.repScore } ?: pool.first()
    }

    /** Keeps a filter's result, or falls back to [fallback] when the filter emptied it. */
    private fun List<DetectedFace>.orAll(fallback: List<DetectedFace>): List<DetectedFace> =
        ifEmpty { fallback }
}
