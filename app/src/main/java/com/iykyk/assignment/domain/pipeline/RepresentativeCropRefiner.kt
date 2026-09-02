package com.iykyk.assignment.domain.pipeline

import android.graphics.Rect
import android.net.Uri
import com.iykyk.assignment.domain.ml.FaceAlignmentHelper
import com.iykyk.assignment.domain.ml.FaceDetectorEngine
import com.iykyk.assignment.domain.model.PersonCluster
import kotlin.math.max
import kotlin.math.min

/**
 * Re-cuts each person's chosen shot from a full-resolution decode of its source frame.
 *
 * The analysis pass runs at a resolution chosen so that a whole video can be swept without
 * exhausting memory, which is a different constraint from the one that governs how good a
 * single tile can look. Since only one frame per person is ever displayed, those frames can
 * be decoded again at full resolution - a few seeks in total - and the tile stops being
 * limited by the analysis resolution.
 *
 * Detection is re-run on the high-resolution frame rather than simply rescaling the stored
 * box. It costs one detector call per person and it is what catches a neighbouring face
 * that was too small to resolve during the sweep, which is precisely the face that would
 * otherwise reappear inside the finished tile.
 */
class RepresentativeCropRefiner(
    private val frameExtractor: VideoFrameExtractor,
    private val faceDetector: FaceDetectorEngine
) {

    companion object {
        /** Longest edge for the re-decoded frame. */
        const val REFINE_FRAME_EDGE = 1920

        /** Longest edge of the produced crop; comfortably above the collage tile size. */
        const val CROP_MAX_EDGE = 1200

        /** Minimum overlap for a high-resolution detection to be the same face. */
        private const val MIN_MATCH_IOU = 0.3f
    }

    suspend fun refine(videoUri: Uri, clusters: List<PersonCluster>): List<PersonCluster> =
        clusters.map { cluster -> refineOne(videoUri, cluster) }

    private suspend fun refineOne(videoUri: Uri, cluster: PersonCluster): PersonCluster {
        val shot = cluster.representativeShot
        val originalBox = shot.boundingBox ?: return cluster
        if (shot.frameWidth <= 0 || shot.frameHeight <= 0) return cluster

        val frame = frameExtractor.decodeFrameAt(videoUri, shot.timestampMs, REFINE_FRAME_EDGE)
            ?: return cluster

        return try {
            if (frame.width <= shot.frameWidth) return cluster

            val scale = frame.width.toFloat() / shot.frameWidth
            val scaledBox = originalBox.scaled(scale, frame.width, frame.height)

            val detected = faceDetector.detectFacesInFrame(frame, shot.frameIndex, shot.timestampMs)
            val allBoxes = detected.mapNotNull { it.boundingBox }

            // Prefer the high-resolution detection that lines up with the face we chose;
            // fall back to the rescaled box when the re-decode landed on a nearby frame.
            val targetBox = allBoxes.maxByOrNull { iou(it, scaledBox) }
                ?.takeIf { iou(it, scaledBox) >= MIN_MATCH_IOU }
                ?: scaledBox

            val neighbours = if (allBoxes.isEmpty()) {
                shot.otherFaceBoxesInFrame.map { it.scaled(scale, frame.width, frame.height) }
            } else {
                allBoxes
            }

            val (crop, plan) = FaceAlignmentHelper.cropPortrait(
                frame = frame,
                box = targetBox,
                otherBoxes = neighbours,
                maxEdge = CROP_MAX_EDGE
            )

            // Only adopt the refined tile if it is at least as clean as the one we had.
            if (!plan.isClean && shot.hasCleanCrop) {
                crop.recycle()
                return cluster
            }

            // The superseded crop is left to the collector rather than recycled: it may
            // still be referenced by the progress UI that displayed it during the sweep.
            cluster.copy(
                representativeShot = shot.copy(
                    generousCropBitmap = crop,
                    hasCleanCrop = plan.isClean
                )
            )
        } catch (e: Exception) {
            cluster
        } finally {
            if (!frame.isRecycled) frame.recycle()
        }
    }

    private fun Rect.scaled(scale: Float, maxW: Int, maxH: Int) = Rect(
        (left * scale).toInt().coerceIn(0, maxW),
        (top * scale).toInt().coerceIn(0, maxH),
        (right * scale).toInt().coerceIn(0, maxW),
        (bottom * scale).toInt().coerceIn(0, maxH)
    )

    private fun iou(a: Rect, b: Rect): Float {
        val interW = min(a.right, b.right) - max(a.left, b.left)
        val interH = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (interW <= 0 || interH <= 0) return 0f

        val inter = interW.toFloat() * interH
        val union = a.width().toFloat() * a.height() + b.width().toFloat() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
