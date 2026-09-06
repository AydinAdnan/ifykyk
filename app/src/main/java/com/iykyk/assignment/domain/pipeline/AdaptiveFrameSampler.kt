package com.iykyk.assignment.domain.pipeline

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * Adaptive Frame Sampler.
 * Replaces fixed-rate sampling with dynamic rate control based on scene dynamics:
 * - Static scene: 1 FPS (1000ms step)
 * - Normal scene: 3 FPS (333ms step)
 * - Fast motion / rapid action: 5 FPS (200ms step)
 *
 * Guarantees bounded latency and avoids processing unnecessary frames on static shots.
 */
class AdaptiveFrameSampler(
    private val staticFps: Float = 1.0f,
    private val normalFps: Float = 3.0f,
    private val fastMotionFps: Float = 5.0f
) {

    private val staticStepMs: Long = (1000f / staticFps).toLong() // 1000ms
    private val normalStepMs: Long = (1000f / normalFps).toLong() // 333ms
    private val fastStepMs: Long = (1000f / fastMotionFps).toLong() // 200ms

    private var previousThumb: FloatArray? = null

    fun reset() {
        previousThumb = null
    }

    /**
     * Determines the optimal time increment to the next frame based on the current frame's visual delta.
     */
    fun computeNextIntervalMs(currentFrame: Bitmap): Long {
        val size = 32
        val scaled = Bitmap.createScaledBitmap(currentFrame, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== currentFrame) scaled.recycle()

        val luma = FloatArray(size * size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        val prev = previousThumb
        previousThumb = luma

        if (prev == null) {
            return normalStepMs
        }

        // Compute Mean Absolute Difference (MAD)
        var mad = 0.0
        for (i in luma.indices) {
            mad += abs(luma[i] - prev[i])
        }
        val delta = (mad / luma.size).toFloat()

        return when {
            delta < 0.04f -> staticStepMs // Static scene: 1 FPS
            delta > 0.18f -> fastStepMs // Fast motion / high pan: 5 FPS
            else -> normalStepMs // Normal scene: 3 FPS
        }
    }
}
