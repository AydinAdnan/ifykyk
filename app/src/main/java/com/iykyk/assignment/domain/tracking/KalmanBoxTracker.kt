package com.iykyk.assignment.domain.tracking

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Platform-independent bounding box representation for tracking and Kalman filtering.
 * Eliminates Android framework stubbing issues in JVM unit tests while providing seamless
 * conversion to/from android.graphics.RectF and Rect.
 */
data class TrackBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = maxOf(0f, right - left)
    val height: Float get() = maxOf(0f, bottom - top)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val area: Float get() = width * height

    fun toRectF(): RectF = RectF(left, top, right, bottom)

    companion object {
        fun fromRectF(r: RectF?): TrackBox {
            if (r == null) return TrackBox(0f, 0f, 0f, 0f)
            return TrackBox(r.left, r.top, r.right, r.bottom)
        }

        fun fromRect(r: Rect?): TrackBox {
            if (r == null) return TrackBox(0f, 0f, 0f, 0f)
            return TrackBox(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        }
    }
}

/**
 * 7-State Linear Kalman Filter for Bounding Box Motion Tracking:
 * State vector: [x_c, y_c, s, r, vx, vy, vs]
 * - x_c, y_c: center coordinates
 * - s: scale (box area = width * height)
 * - r: aspect ratio (width / height)
 * - vx, vy, vs: velocity terms
 */
class KalmanBoxTracker(
    initLeft: Float,
    initTop: Float,
    initRight: Float,
    initBottom: Float
) {
    constructor(bbox: TrackBox) : this(bbox.left, bbox.top, bbox.right, bbox.bottom)
    constructor(rectF: RectF) : this(rectF.left, rectF.top, rectF.right, rectF.bottom)

    private val initW = maxOf(1f, initRight - initLeft)
    private val initH = maxOf(1f, initBottom - initTop)

    // State estimates
    private var xc = (initLeft + initRight) * 0.5f
    private var yc = (initTop + initBottom) * 0.5f
    private var s = maxOf(1f, initW * initH)
    private var r = initW / maxOf(1f, initH)
    private var vx = 0f
    private var vy = 0f
    private var vs = 0f

    // Covariance diagonals (simplified diagonal Kalman representation for high mobile throughput)
    private var pXc = 10f
    private var pYc = 10f
    private var pS = 10f
    private var pR = 10f
    private var pVx = 100f
    private var pVy = 100f
    private var pVs = 100f

    // Process noise
    private val qPos = 1f
    private val qVel = 0.01f

    // Measurement noise
    private val rPos = 1f

    var timeSinceUpdate = 0
    var hits = 1
    var age = 0

    val currentCenterX: Float get() = xc
    val currentCenterY: Float get() = yc
    val currentWidth: Float get() = sqrt(s * r)
    val currentHeight: Float get() = s / maxOf(1e-4f, currentWidth)

    /**
     * Advances state vector and returns predicted bounding box.
     */
    fun predictBox(): TrackBox {
        xc += vx
        yc += vy
        s += vs
        s = max(1f, s)

        pXc += qPos + pVx
        pYc += qPos + pVy
        pS += qPos + pVs
        pVx += qVel
        pVy += qVel
        pVs += qVel

        age++
        timeSinceUpdate++
        return getCurrentTrackBox()
    }

    fun predict(): RectF = predictBox().toRectF()

    /**
     * Updates state vector using observed measurement bounding box.
     */
    fun update(left: Float, top: Float, right: Float, bottom: Float) {
        timeSinceUpdate = 0
        hits++

        val mW = maxOf(1f, right - left)
        val mH = maxOf(1f, bottom - top)
        val mXc = (left + right) * 0.5f
        val mYc = (top + bottom) * 0.5f
        val mS = maxOf(1f, mW * mH)
        val mR = mW / maxOf(1f, mH)

        // Kalman gain updates for position & velocity
        val kXc = pXc / (pXc + rPos)
        xc += kXc * (mXc - xc)
        vx += (kXc * 0.5f) * (mXc - xc)
        pXc *= (1f - kXc)

        val kYc = pYc / (pYc + rPos)
        yc += kYc * (mYc - yc)
        vy += (kYc * 0.5f) * (mYc - yc)
        pYc *= (1f - kYc)

        val kS = pS / (pS + rPos)
        s += kS * (mS - s)
        vs += (kS * 0.5f) * (mS - s)
        s = max(1f, s)
        pS *= (1f - kS)

        val kR = pR / (pR + rPos)
        r += kR * (mR - r)
        pR *= (1f - kR)
    }

    fun update(bbox: TrackBox) = update(bbox.left, bbox.top, bbox.right, bbox.bottom)
    fun update(bbox: RectF) = update(bbox.left, bbox.top, bbox.right, bbox.bottom)

    fun getCurrentTrackBox(): TrackBox {
        val w = sqrt(s * r)
        val h = s / max(1e-4f, w)
        return TrackBox(
            left = xc - w * 0.5f,
            top = yc - h * 0.5f,
            right = xc + w * 0.5f,
            bottom = yc + h * 0.5f
        )
    }

    fun getCurrentBox(): RectF = getCurrentTrackBox().toRectF()
}
