package com.iykyk.assignment.domain.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object FaceAlignmentHelper {

    /**
     * Aligns and crops a face to standard 112x112 dimensions using eye coordinates.
     */
    fun alignFace112(
        frame: Bitmap,
        box: Rect,
        leftEye: PointF?,
        rightEye: PointF?
    ): Bitmap {
        val targetSize = 112
        val outputBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (leftEye != null && rightEye != null) {
            val dx = rightEye.x - leftEye.x
            val dy = rightEye.y - leftEye.y
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val eyeDist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            val eyeCenterX = (leftEye.x + rightEye.x) / 2f
            val eyeCenterY = (leftEye.y + rightEye.y) / 2f

            // Desired eye distance in 112x112 image is approx 38-42% of width (~44px)
            val desiredEyeDist = 44f
            val scale = desiredEyeDist / max(eyeDist, 10f)

            val matrix = Matrix().apply {
                postTranslate(-eyeCenterX, -eyeCenterY)
                postRotate(-angle)
                postScale(scale, scale)
                postTranslate(targetSize * 0.5f, targetSize * 0.38f)
            }
            canvas.drawBitmap(frame, matrix, paint)
        } else {
            // Fallback: direct crop with aspect ratio
            val srcRect = clampRect(box, frame.width, frame.height)
            val matrix = Matrix().apply {
                val scale = targetSize.toFloat() / max(srcRect.width(), srcRect.height()).coerceAtLeast(1)
                postTranslate(-srcRect.left.toFloat(), -srcRect.top.toFloat())
                postScale(scale, scale)
            }
            canvas.drawBitmap(frame, matrix, paint)
        }

        return outputBitmap
    }

    /**
     * Generous crop around the face (adds 40% margin) for the final scrapbook stamp collage.
     * Brief rule: no tight bounding-box crops.
     */
    fun cropGenerousFace(frame: Bitmap, box: Rect): Bitmap {
        val marginX = (box.width() * 0.45f).toInt()
        val marginY = (box.height() * 0.45f).toInt()

        val left = max(0, box.left - marginX)
        val top = max(0, box.top - marginY)
        val right = min(frame.width, box.right + marginX)
        val bottom = min(frame.height, box.bottom + marginY)

        val width = max(1, right - left)
        val height = max(1, bottom - top)

        return Bitmap.createBitmap(frame, left, top, width, height)
    }

    /**
     * Variance of Laplacian / edge gradient to score face sharpness.
     */
    fun computeSharpness(crop: Bitmap): Float {
        val w = crop.width
        val h = crop.height
        if (w < 4 || h < 4) return 0f

        // Sample grayscale pixels
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumGrad = 0.0
        var count = 0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val center = (pixels[y * w + x] and 0xFF)
                val left = (pixels[y * w + (x - 1)] and 0xFF)
                val right = (pixels[y * w + (x + 1)] and 0xFF)
                val top = (pixels[(y - 1) * w + x] and 0xFF)
                val bottom = (pixels[(y + 1) * w + x] and 0xFF)

                val lap = Math.abs(4 * center - left - right - top - bottom)
                sumGrad += lap
                count++
            }
        }

        return if (count > 0) (sumGrad / count).toFloat() else 0f
    }

    private fun clampRect(r: Rect, maxW: Int, maxH: Int): Rect {
        return Rect(
            max(0, min(r.left, maxW - 1)),
            max(0, min(r.top, maxH - 1)),
            max(1, min(r.right, maxW)),
            max(1, min(r.bottom, maxH))
        )
    }
}
