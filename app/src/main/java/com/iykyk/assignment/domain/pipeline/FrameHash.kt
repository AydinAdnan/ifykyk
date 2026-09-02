package com.iykyk.assignment.domain.pipeline

/**
 * 64-bit average hash of a frame, used to spot frames that are effectively identical.
 *
 * Seeking to the nearest keyframe means several requested timestamps can land on the same
 * decoded frame, and a static shot yields near-identical frames regardless. Both are pure
 * waste: running face detection on them costs the same as on a new frame and tells us
 * nothing we do not already know.
 */
object FrameHash {

    /** Hamming distance at or below which two frames are treated as the same frame. */
    const val DUPLICATE_DISTANCE = 2

    /**
     * Computes the average hash of an 8x8 luminance reduction: each bit records whether
     * that cell is brighter than the frame mean.
     *
     * @param luma row-major 8x8 luminance samples
     */
    fun ofLumaGrid(luma: FloatArray): Long {
        require(luma.size == 64) { "Expected an 8x8 grid, got ${luma.size} samples" }

        var sum = 0.0
        for (v in luma) sum += v
        val mean = sum / luma.size

        var hash = 0L
        for (i in luma.indices) {
            if (luma[i] > mean) hash = hash or (1L shl i)
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun isDuplicate(a: Long, b: Long): Boolean = distance(a, b) <= DUPLICATE_DISTANCE
}
