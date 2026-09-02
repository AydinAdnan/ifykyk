package com.iykyk.assignment.domain.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import com.iykyk.assignment.domain.pipeline.Box
import com.iykyk.assignment.domain.pipeline.CropPlan
import com.iykyk.assignment.domain.pipeline.PortraitCropPlanner
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

object FaceAlignmentHelper {

    /** Fixed patch size used for scale-comparable blur scoring. */
    private const val SHARPNESS_PATCH = 128

    /**
     * Produces the aligned square crop fed to the recognition model.
     *
     * FaceNet was trained on MTCNN outputs: a square region around the face box with a
     * fixed margin, resized to 160x160, with no rotation normalisation. Reproducing that
     * framing matters as much as the pixel normalisation does - an ArcFace-style tight
     * eye-distance alignment puts the face at a scale the network never saw.
     *
     * Roll is still levelled using the eye landmarks when both are available, since
     * in-plane rotation is the one nuisance transform that costs accuracy and that we can
     * remove exactly.
     *
     * @param faceRatio fraction of the output edge spanned by the face box (FaceNet's
     *   32px margin on a 160px crop corresponds to 0.8).
     */
    fun alignFace(
        frame: Bitmap,
        box: Rect,
        leftEye: PointF?,
        rightEye: PointF?,
        outputSize: Int,
        faceRatio: Float = 0.8f
    ): Bitmap {
        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val centerX = box.exactCenterX()
        val centerY = box.exactCenterY()
        val faceSpan = max(box.width(), box.height()).toFloat().coerceAtLeast(1f)
        val scale = (outputSize * faceRatio) / faceSpan

        val rollDegrees = if (leftEye != null && rightEye != null) {
            val dx = rightEye.x - leftEye.x
            val dy = rightEye.y - leftEye.y
            Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        } else {
            0f
        }

        val matrix = Matrix().apply {
            postTranslate(-centerX, -centerY)
            // Only correct plausible head tilt; a wild landmark pair should not spin the crop.
            if (Math.abs(rollDegrees) <= 35f) postRotate(-rollDegrees)
            postScale(scale, scale)
            postTranslate(outputSize * 0.5f, outputSize * 0.5f)
        }
        canvas.drawBitmap(frame, matrix, paint)

        return output
    }

    /**
     * Renders the planned portrait crop for a face into an independent bitmap.
     *
     * Crop geometry is decided by [PortraitCropPlanner] on plain integer boxes; this only
     * turns the resulting region into pixels, downscaled so the longest edge is at most
     * [maxEdge].
     */
    fun cropPortrait(
        frame: Bitmap,
        box: Rect,
        otherBoxes: List<Rect> = emptyList(),
        maxEdge: Int = 900
    ): Pair<Bitmap, CropPlan> {
        val plan = PortraitCropPlanner.plan(
            face = box.toPlannerBox(),
            others = otherBoxes.map { it.toPlannerBox() },
            frameWidth = frame.width,
            frameHeight = frame.height
        )
        val region = plan.box
        val bitmap = copyRegion(frame, region.left, region.top, region.width, region.height, maxEdge)
        return bitmap to plan
    }

    private fun Rect.toPlannerBox() = Box(left, top, right, bottom)

    /**
     * Copies a region out of [frame] into an independent bitmap, downscaled so its longest
     * edge is at most [maxEdge]. Always returns a fresh bitmap: the caller recycles source
     * frames as it streams, and Bitmap.createBitmap can alias the source for full-bounds
     * subsets, which would leave the crop pointing at recycled pixels.
     */
    fun copyRegion(frame: Bitmap, x: Int, y: Int, w: Int, h: Int, maxEdge: Int): Bitmap {
        val srcW = w.coerceAtMost(frame.width - x).coerceAtLeast(1)
        val srcH = h.coerceAtMost(frame.height - y).coerceAtLeast(1)

        val scale = min(1f, maxEdge.toFloat() / max(srcW, srcH))
        val dstW = max(1, (srcW * scale).toInt())
        val dstH = max(1, (srcH * scale).toInt())

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            frame,
            Rect(x, y, x + srcW, y + srcH),
            Rect(0, 0, dstW, dstH),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    /**
     * Blur score for a face, as the variance of the Laplacian over a fixed-size
     * luminance patch. Higher is sharper.
     *
     * Two details matter. The patch is always resampled to [SHARPNESS_PATCH] so scores
     * are comparable between a large close-up and a small background face, and a face
     * whose native resolution is below the patch size scores low - which is what we want,
     * because it genuinely has no detail to show in a collage tile. And it reads real
     * luminance: sampling `pixel and 0xFF` measures the blue channel alone, which tracks
     * focus only incidentally and collapses on warm or saturated footage.
     */
    fun computeSharpness(frame: Bitmap, region: Rect? = null): Float {
        val src = region ?: Rect(0, 0, frame.width, frame.height)
        val x = src.left.coerceIn(0, max(0, frame.width - 1))
        val y = src.top.coerceIn(0, max(0, frame.height - 1))
        val w = src.width().coerceAtMost(frame.width - x)
        val h = src.height().coerceAtMost(frame.height - y)
        if (w < 4 || h < 4) return 0f

        val patch = copyRegionExact(frame, x, y, w, h, SHARPNESS_PATCH)
        val size = SHARPNESS_PATCH
        val pixels = IntArray(size * size)
        patch.getPixels(pixels, 0, size, 0, 0, size, size)
        patch.recycle()

        val luma = FloatArray(size * size)
        for (i in pixels.indices) {
            val c = pixels[i]
            luma[i] = 0.299f * ((c shr 16) and 0xFF) +
                0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (yy in 1 until size - 1) {
            for (xx in 1 until size - 1) {
                val i = yy * size + xx
                val lap = 4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - size] - luma[i + size]
                sum += lap
                sumSq += (lap * lap).toDouble()
                count++
            }
        }
        if (count == 0) return 0f

        val mean = sum / count
        return ((sumSq / count) - mean * mean).coerceAtLeast(0.0).toFloat()
    }

    /** Copies a region and resizes it to exactly [size] x [size]. */
    private fun copyRegionExact(frame: Bitmap, x: Int, y: Int, w: Int, h: Int, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            frame,
            Rect(x, y, x + w, y + h),
            Rect(0, 0, size, size),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }
}
