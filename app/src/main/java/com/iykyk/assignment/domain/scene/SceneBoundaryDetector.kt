package com.iykyk.assignment.domain.scene

import android.graphics.Bitmap
import android.graphics.Color
import com.iykyk.assignment.domain.model.Scene
import com.iykyk.assignment.domain.model.SceneTransitionType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Robust Scene Boundary Detector.
 * Identifies cuts, dissolves, fades, zooms, and motion blur cuts.
 * Invariant: Tracking must NEVER propagate across confirmed scene boundaries.
 */
class SceneBoundaryDetector(
    private val cutThreshold: Float = 0.42f,
    private val dissolveThreshold: Float = 0.28f,
    private val minSceneDurationMs: Long = 600L
) {

    private data class FrameSignature(
        val timestampMs: Long,
        val frameIndex: Int,
        val hsvHist: FloatArray, // 16 hue x 4 sat x 4 val = 256 bins
        val meanBrightness: Float,
        val edgeDensity: Float,
        val luma64: FloatArray // 64x64 luma thumbnail for SSIM
    )

    private val history = mutableListOf<FrameSignature>()
    private val confirmedCuts = mutableListOf<Long>() // Timestamps where cuts occur

    fun reset() {
        history.clear()
        confirmedCuts.clear()
    }

    /**
     * Evaluates a sampled frame and returns the detected scene transition type if a boundary occurred,
     * or null if this frame is a continuous part of the active scene.
     */
    fun processFrame(bitmap: Bitmap, frameIndex: Int, timestampMs: Long): SceneTransitionType? {
        val sig = extractSignature(bitmap, frameIndex, timestampMs)
        if (history.isEmpty()) {
            history.add(sig)
            return null
        }

        val prev = history.last()
        val hsvDiff = computeHistDiff(prev.hsvHist, sig.hsvHist)
        val ssim = computeSsim(prev.luma64, sig.luma64)
        val brightnessChange = abs(sig.meanBrightness - prev.meanBrightness)
        val edgeChange = abs(sig.edgeDensity - prev.edgeDensity) / max(0.01f, max(prev.edgeDensity, sig.edgeDensity))

        var transition: SceneTransitionType? = null

        // 1. Hard Cut: sharp histogram drop + low SSIM + high edge shift
        if (hsvDiff > cutThreshold && ssim < 0.65f) {
            transition = SceneTransitionType.HARD_CUT
        }
        // 2. Fade Out / Fade In (brightness drops to near 0 or surges from 0)
        else if ((prev.meanBrightness < 0.08f && sig.meanBrightness > 0.18f) ||
            (prev.meanBrightness > 0.18f && sig.meanBrightness < 0.08f)) {
            transition = if (sig.meanBrightness > prev.meanBrightness) SceneTransitionType.FADE_IN else SceneTransitionType.FADE_OUT
        }
        // 3. Cross Dissolve: gradual histogram divergence + low SSIM with moderate edge decay
        else if (hsvDiff > dissolveThreshold && ssim < 0.72f && edgeChange < 0.35f) {
            transition = SceneTransitionType.CROSS_DISSOLVE
        }
        // 4. Zoom / Motion Blur Transition: large edge loss with relatively consistent color
        else if (edgeChange > 0.65f && hsvDiff > dissolveThreshold && ssim < 0.60f) {
            transition = SceneTransitionType.ZOOM_TRANSITION
        }

        history.add(sig)
        if (history.size > 200) {
            history.removeAt(0)
        }

        if (transition != null) {
            val lastCut = confirmedCuts.lastOrNull() ?: 0L
            if (timestampMs - lastCut >= minSceneDurationMs) {
                confirmedCuts.add(timestampMs)
                return transition
            }
        }

        return null
    }

    /**
     * Reconstructs all complete scenes from the recorded video timeline.
     */
    fun buildScenes(totalDurationMs: Long): List<Scene> {
        if (confirmedCuts.isEmpty()) {
            return listOf(
                Scene(
                    id = 1,
                    startTimestamp = 0L,
                    endTimestamp = totalDurationMs,
                    confidence = 1.0f,
                    transitionType = SceneTransitionType.HARD_CUT
                )
            )
        }

        val scenes = mutableListOf<Scene>()
        var start = 0L
        var id = 1

        for (cutTime in confirmedCuts) {
            if (cutTime > start) {
                scenes.add(
                    Scene(
                        id = id++,
                        startTimestamp = start,
                        endTimestamp = cutTime,
                        confidence = 0.95f,
                        transitionType = SceneTransitionType.HARD_CUT
                    )
                )
                start = cutTime
            }
        }

        if (start < totalDurationMs) {
            scenes.add(
                Scene(
                    id = id,
                    startTimestamp = start,
                    endTimestamp = totalDurationMs,
                    confidence = 0.95f,
                    transitionType = SceneTransitionType.HARD_CUT
                )
            )
        }

        return scenes
    }

    private fun extractSignature(bitmap: Bitmap, frameIndex: Int, timestampMs: Long): FrameSignature {
        val thumbSize = 64
        val scaled = Bitmap.createScaledBitmap(bitmap, thumbSize, thumbSize, true)
        val pixels = IntArray(thumbSize * thumbSize)
        scaled.getPixels(pixels, 0, thumbSize, 0, 0, thumbSize, thumbSize)
        if (scaled !== bitmap) scaled.recycle()

        val hsvBins = FloatArray(16 * 4 * 4) // 256
        val luma64 = FloatArray(thumbSize * thumbSize)
        var totalBrightness = 0.0
        val hsv = FloatArray(3)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            luma64[i] = luma
            totalBrightness += luma

            Color.RGBToHSV(r, g, b, hsv)
            val hBin = ((hsv[0] / 360f) * 15.99f).toInt().coerceIn(0, 15)
            val sBin = (hsv[1] * 3.99f).toInt().coerceIn(0, 3)
            val vBin = (hsv[2] * 3.99f).toInt().coerceIn(0, 3)
            val binIdx = hBin * 16 + sBin * 4 + vBin
            hsvBins[binIdx] += 1f
        }

        // L1 normalize histogram
        val totalPix = pixels.size.toFloat()
        for (i in hsvBins.indices) {
            hsvBins[i] /= totalPix
        }

        // Edge density using Sobel on 64x64 luma
        var edgeSum = 0.0
        for (y in 1 until thumbSize - 1) {
            for (x in 1 until thumbSize - 1) {
                val gx = (luma64[(y - 1) * thumbSize + (x + 1)] + 2 * luma64[y * thumbSize + (x + 1)] + luma64[(y + 1) * thumbSize + (x + 1)]) -
                    (luma64[(y - 1) * thumbSize + (x - 1)] + 2 * luma64[y * thumbSize + (x - 1)] + luma64[(y + 1) * thumbSize + (x - 1)])
                val gy = (luma64[(y + 1) * thumbSize + (x - 1)] + 2 * luma64[(y + 1) * thumbSize + x] + luma64[(y + 1) * thumbSize + (x + 1)]) -
                    (luma64[(y - 1) * thumbSize + (x - 1)] + 2 * luma64[(y - 1) * thumbSize + x] + luma64[(y - 1) * thumbSize + (x + 1)])
                edgeSum += sqrt((gx * gx + gy * gy).toDouble())
            }
        }

        val edgeDensity = (edgeSum / ((thumbSize - 2) * (thumbSize - 2))).toFloat()

        return FrameSignature(
            timestampMs = timestampMs,
            frameIndex = frameIndex,
            hsvHist = hsvBins,
            meanBrightness = (totalBrightness / pixels.size).toFloat(),
            edgeDensity = edgeDensity,
            luma64 = luma64
        )
    }

    private fun computeHistDiff(h1: FloatArray, h2: FloatArray): Float {
        // Chi-square distance
        var dist = 0.0
        for (i in h1.indices) {
            val num = (h1[i] - h2[i]).toDouble()
            val denom = (h1[i] + h2[i]).toDouble()
            if (denom > 1e-6) {
                dist += (num * num) / denom
            }
        }
        return (dist * 0.5).coerceIn(0.0, 1.0).toFloat()
    }

    private fun computeSsim(l1: FloatArray, l2: FloatArray): Float {
        var mean1 = 0.0
        var mean2 = 0.0
        val n = l1.size
        for (i in 0 until n) {
            mean1 += l1[i]
            mean2 += l2[i]
        }
        mean1 /= n
        mean2 /= n

        var var1 = 0.0
        var var2 = 0.0
        var cov = 0.0
        for (i in 0 until n) {
            val d1 = l1[i] - mean1
            val d2 = l2[i] - mean2
            var1 += d1 * d1
            var2 += d2 * d2
            cov += d1 * d2
        }
        var1 /= n
        var2 /= n
        cov /= n

        val c1 = 0.0001
        val c2 = 0.0009
        val ssim = ((2 * mean1 * mean2 + c1) * (2 * cov + c2)) /
            ((mean1 * mean1 + mean2 * mean2 + c1) * (var1 + var2 + c2))
        return ssim.coerceIn(0.0, 1.0).toFloat()
    }
}
