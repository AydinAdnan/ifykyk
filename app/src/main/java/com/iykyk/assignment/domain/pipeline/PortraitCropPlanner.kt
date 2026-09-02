package com.iykyk.assignment.domain.pipeline

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Plain-Kotlin rectangle. Deliberately not android.graphics.Rect so the whole planner is
 * exercisable in JVM unit tests, where the android.jar stubs return default values.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Long get() = max(0, width).toLong() * max(0, height).toLong()

    fun intersects(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun contains(other: Box): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/**
 * Result of planning a portrait crop for one face.
 *
 * @param box the crop region in frame coordinates
 * @param isClean true when no other detected face overlaps the crop at all
 * @param faceCoverage the target face height as a fraction of the crop height; low values
 *   mean a wide, contextual shot and high values mean the crop had to close in
 */
data class CropPlan(
    val box: Box,
    val isClean: Boolean,
    val faceCoverage: Float
)

/**
 * Chooses a generous, portrait-shaped crop around one face that excludes every other
 * detected face in the frame.
 *
 * The previous approach expanded a fixed margin around the face box and then, only for
 * neighbours lying entirely to one side, pulled the edge back to the midpoint between the
 * two faces. That leaves half of the neighbour in the shot, and does nothing at all for a
 * neighbour that overlaps diagonally or that sits behind the subject - which is why group
 * shots kept producing tiles with a second, half-cropped person in them.
 *
 * Here the crop starts generous and each overlapping neighbour is excluded outright by
 * retracting whichever single edge costs the least area, never cutting into the subject
 * face plus its safety margin. If no neighbour-free crop exists, the tightest safe crop is
 * returned with isClean = false so callers can prefer a different frame instead.
 */
object PortraitCropPlanner {

    /** Portrait aspect ratio (width / height) for the produced crops. */
    const val ASPECT = 4f / 5f

    /** Crop width as a multiple of the face box width, before any constraints. */
    private const val WIDTH_FACE_MULTIPLE = 2.6f

    /** Where the face centre sits vertically in the crop; above centre reads better. */
    private const val FACE_VERTICAL_ANCHOR = 0.42f

    /** Breathing room kept around the subject face, as a fraction of its width. */
    private const val SUBJECT_MARGIN = 0.18f

    /** A crop tighter than this multiple of the face width is not worth keeping. */
    private const val MIN_WIDTH_FACE_MULTIPLE = 1.25f

    fun plan(
        face: Box,
        others: List<Box>,
        frameWidth: Int,
        frameHeight: Int
    ): CropPlan {
        val frame = Box(0, 0, frameWidth, frameHeight)

        // The subject plus a small margin is inviolable: no exclusion may eat into it.
        val marginX = (face.width * SUBJECT_MARGIN).roundToInt()
        val marginY = (face.height * SUBJECT_MARGIN).roundToInt()
        val protected = Box(
            max(0, face.left - marginX),
            max(0, face.top - marginY),
            min(frameWidth, face.right + marginX),
            min(frameHeight, face.bottom + marginY)
        )

        var crop = initialCrop(face, frame)

        val neighbours = others.filter { it != face && it.intersects(protected).not() && it.width > 0 }
        for (neighbour in neighbours.sortedByDescending { it.area }) {
            if (!crop.intersects(neighbour)) continue
            crop = excludeNeighbour(crop, neighbour, protected) ?: crop
        }

        val stillOverlapping = others.any { it != face && it.intersects(crop) }
        val tooTight = crop.width < face.width * MIN_WIDTH_FACE_MULTIPLE

        val finalCrop = if (tooTight) {
            // Better a slightly tight portrait than one sliced to a sliver.
            clampToFrame(expandToAspect(protected, frame), frame)
        } else {
            clampToFrame(crop, frame)
        }

        val coverage = if (finalCrop.height > 0) face.height.toFloat() / finalCrop.height else 1f
        return CropPlan(
            box = finalCrop,
            isClean = !stillOverlapping && !tooTight,
            faceCoverage = coverage
        )
    }

    /** The generous portrait window we would use if the frame held only this face. */
    private fun initialCrop(face: Box, frame: Box): Box {
        var width = face.width * WIDTH_FACE_MULTIPLE
        var height = width / ASPECT

        // Never ask for more than the frame can give.
        if (width > frame.width) {
            width = frame.width.toFloat()
            height = width / ASPECT
        }
        if (height > frame.height) {
            height = frame.height.toFloat()
            width = height * ASPECT
        }

        val left = face.centerX - width / 2f
        val top = face.centerY - height * FACE_VERTICAL_ANCHOR

        return clampToFrame(
            Box(
                left.roundToInt(),
                top.roundToInt(),
                (left + width).roundToInt(),
                (top + height).roundToInt()
            ),
            frame
        )
    }

    /**
     * Retracts the single crop edge that removes [neighbour] at the lowest cost in area,
     * refusing any retraction that would clip [protected]. Returns null when every option
     * would cut into the subject.
     */
    private fun excludeNeighbour(crop: Box, neighbour: Box, protected: Box): Box? {
        val candidates = listOf(
            crop.copy(right = min(crop.right, neighbour.left)),
            crop.copy(left = max(crop.left, neighbour.right)),
            crop.copy(bottom = min(crop.bottom, neighbour.top)),
            crop.copy(top = max(crop.top, neighbour.bottom))
        )

        return candidates
            .filter { it.width > 0 && it.height > 0 && it.contains(protected) }
            .maxByOrNull { it.area }
    }

    /** Grows [box] to the portrait aspect ratio, as far as [frame] allows. */
    private fun expandToAspect(box: Box, frame: Box): Box {
        val currentAspect = if (box.height == 0) ASPECT else box.width.toFloat() / box.height
        if (currentAspect > ASPECT) {
            val targetHeight = box.width / ASPECT
            val grow = (targetHeight - box.height) / 2f
            return Box(box.left, (box.top - grow).roundToInt(), box.right, (box.bottom + grow).roundToInt())
        }
        val targetWidth = box.height * ASPECT
        val grow = (targetWidth - box.width) / 2f
        return Box((box.left - grow).roundToInt(), box.top, (box.right + grow).roundToInt(), box.bottom)
    }

    /** Slides [box] inside [frame] where possible, and only shrinks it when it cannot fit. */
    private fun clampToFrame(box: Box, frame: Box): Box {
        var left = box.left
        var right = box.right
        var top = box.top
        var bottom = box.bottom

        if (right - left > frame.width) {
            left = 0
            right = frame.width
        } else {
            if (left < 0) {
                right -= left
                left = 0
            }
            if (right > frame.width) {
                left -= right - frame.width
                right = frame.width
            }
            left = max(0, left)
        }

        if (bottom - top > frame.height) {
            top = 0
            bottom = frame.height
        } else {
            if (top < 0) {
                bottom -= top
                top = 0
            }
            if (bottom > frame.height) {
                top -= bottom - frame.height
                bottom = frame.height
            }
            top = max(0, top)
        }

        return Box(left, top, right, bottom)
    }
}
