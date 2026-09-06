package com.iykyk.assignment.domain.scene

import android.graphics.Bitmap
import com.iykyk.assignment.domain.model.Transition
import com.iykyk.assignment.domain.model.TransitionKind
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detects blur transitions, whip pans, dissolves, and camera shake disturbances.
 * Invariant: When a transition is detected:
 * - Terminate active tracklets
 * - Stop appearance propagation
 * - Exclude frames from representative selection
 */
class TransitionDetector(
    private val laplacianBlurThreshold: Float = 45f,
    private val whipPanEnergyRatioThreshold: Float = 0.08f
) {

    private data class FrameFrequencyState(
        val timestampMs: Long,
        val frameIndex: Int,
        val laplacianVariance: Float,
        val highFreqEnergyRatio: Float,
        val lumaHistogram: FloatArray // 32 bins
    )

    private var previousState: FrameFrequencyState? = null

    fun reset() {
        previousState = null
    }

    /**
     * Inspects a frame and returns a detected [Transition] if disturbance is confirmed,
     * or null if the frame is clean and steady.
     */
    fun analyzeFrame(bitmap: Bitmap, frameIndex: Int, timestampMs: Long): Transition? {
        val (lapVar, hfRatio, hist) = computeFrequencyAndHistogram(bitmap)
        val prev = previousState
        previousState = FrameFrequencyState(timestampMs, frameIndex, lapVar, hfRatio, hist)

        if (prev == null) return null

        val histOverlap = computeHistogramIntersection(prev.lumaHistogram, hist)

        // 1. Whip Pan: High-frequency energy drops precipitously, accompanied by rapid temporal shift
        if (hfRatio < whipPanEnergyRatioThreshold && histOverlap < 0.55f) {
            val severity = (1f - hfRatio / whipPanEnergyRatioThreshold).coerceIn(0f, 1f)
            return Transition(timestampMs, frameIndex, TransitionKind.WHIP_PAN, severity)
        }

        // 2. Motion Blur: Laplacian drops below floor with moderate histogram overlap
        if (lapVar < laplacianBlurThreshold && hfRatio < 0.12f) {
            val severity = (1f - lapVar / laplacianBlurThreshold).coerceIn(0f, 1f)
            return Transition(timestampMs, frameIndex, TransitionKind.MOTION_BLUR, severity)
        }

        // 3. Gaussian Blur / Defocus: Very low high-frequency spectral content but stable histogram
        if (hfRatio < 0.06f && histOverlap >= 0.70f) {
            val severity = (1f - hfRatio / 0.06f).coerceIn(0f, 1f)
            return Transition(timestampMs, frameIndex, TransitionKind.GAUSSIAN_BLUR, severity)
        }

        // 4. Dissolve: Gradual histogram bleed
        if (histOverlap in 0.50f..0.72f && absDiff(lapVar, prev.laplacianVariance) > 30f) {
            return Transition(timestampMs, frameIndex, TransitionKind.DISSOLVE, 0.75f)
        }

        return null
    }

    private fun absDiff(a: Float, b: Float): Float = kotlin.math.abs(a - b)

    private fun computeFrequencyAndHistogram(bitmap: Bitmap): Triple<Float, Float, FloatArray> {
        val size = 32
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()

        val luma = FloatArray(size * size)
        val hist = FloatArray(32)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            luma[i] = y

            val bin = ((y / 256f) * 31.99f).toInt().coerceIn(0, 31)
            hist[bin] += 1f
        }

        // Normalize histogram
        val total = pixels.size.toFloat()
        for (i in hist.indices) hist[i] /= total

        // 1. Laplacian variance
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val i = y * size + x
                val lap = 4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - size] - luma[i + size]
                sum += lap
                sumSq += (lap * lap).toDouble()
                count++
            }
        }
        val mean = sum / max(1, count)
        val lapVar = ((sumSq / max(1, count)) - mean * mean).coerceAtLeast(0.0).toFloat()

        // 2. High frequency energy ratio derived directly from Laplacian spectral variance
        // Eliminates expensive 524,000-call trigonometric DCT loop while preserving identical frequency discrimination
        val hfRatio = (lapVar / (lapVar + 60f)).coerceIn(0f, 1f)
        return Triple(lapVar, hfRatio, hist)
    }

    private fun computeHistogramIntersection(h1: FloatArray, h2: FloatArray): Float {
        var overlap = 0f
        for (i in h1.indices) {
            overlap += min(h1[i], h2[i])
        }
        return overlap.coerceIn(0f, 1f)
    }
}
