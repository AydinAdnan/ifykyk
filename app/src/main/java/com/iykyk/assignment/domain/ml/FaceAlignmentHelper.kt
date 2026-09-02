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
     * Generous centered crop around the face for beautiful Polaroid framing.
     * Aligns center on face and includes hair, chin, and upper collar.
     */
    fun cropGenerousFace(
        frame: Bitmap,
        box: Rect,
        otherBoxes: List<Rect> = emptyList(),
        maxEdge: Int = 720
    ): Bitmap {
        val faceW = box.width().toFloat()
        val faceH = box.height().toFloat()

        val marginX = (faceW * 0.40f).toInt()
        val marginTop = (faceH * 0.35f).toInt()
        val marginBottom = (faceH * 0.45f).toInt()

        var left = max(0, box.left - marginX)
        var top = max(0, box.top - marginTop)
        var right = min(frame.width, box.right + marginX)
        var bottom = min(frame.height, box.bottom + marginBottom)

        // Avoid encroaching on other detected faces in the same frame
        for (other in otherBoxes) {
            if (other == box) continue
            if (other.left >= box.right) {
                val midX = (box.right + other.left) / 2
                right = min(right, midX)
            }
            if (other.right <= box.left) {
                val midX = (other.right + box.left) / 2
                left = max(left, midX)
            }
            if (other.top >= box.bottom) {
                val midY = (box.bottom + other.top) / 2
                bottom = min(bottom, midY)
            }
            if (other.bottom <= box.top) {
                val midY = (other.bottom + box.top) / 2
                top = max(top, midY)
            }
        }

        val width = max(1, right - left)
        val height = max(1, bottom - top)

        return copyRegion(frame, left, top, width, height, maxEdge)
    }

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
