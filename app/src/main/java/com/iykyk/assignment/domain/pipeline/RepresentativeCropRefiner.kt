package com.iykyk.assignment.domain.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import com.iykyk.assignment.domain.ml.FaceAlignmentHelper
import com.iykyk.assignment.domain.ml.FaceDetectorEngine
import com.iykyk.assignment.domain.model.DetectedFace
import com.iykyk.assignment.domain.model.PersonCluster
import kotlin.math.max
import kotlin.math.min

/**
 * Settles each person's representative shot against a higher-resolution decode of its
 * source frame, and re-picks when that decode disproves the choice.
 *
 * The sweep runs at a resolution chosen for throughput, and its verdicts inherit that
 * limit. A background face 30px wide is invisible at sweep resolution, so the crop planner
 * cannot avoid it and the shot is recorded as clean; motion blur is likewise easier to
 * miss in a smaller frame. Both errors surface only here, where the frame is decoded
 * larger.
 *
 * The previous version detected exactly this situation and then kept the disproven shot
 * anyway, falling back to the sweep-resolution crop - the very crop containing the person
 * it had just discovered. Candidates are now tried in preference order until one survives
 * verification, so a frame that turns out to hold two people, or to be blurred, is
 * abandoned in favour of the person's next-best frame.
 */
class RepresentativeCropRefiner(
    private val frameExtractor: VideoFrameExtractor,
    private val faceDetector: FaceDetectorEngine
) {

    companion object {
        /**
         * Longest edge for the verification decode.
         *
         * Enough to resolve neighbours the sweep missed and to judge blur honestly, while
         * staying cheap: detection cost scales with pixel count, and this runs a few times
         * per person.
         */
        const val REFINE_FRAME_EDGE = 1280

        /** Longest edge of the produced crop; well above the collage tile size. */
        const val CROP_MAX_EDGE = 900

        /** How many candidate frames to try per person before settling. */
        const val MAX_CANDIDATES = 4

        /** Minimum overlap for a high-resolution detection to be the same face. */
        private const val MIN_MATCH_IOU = 0.3f

        /**
         * Laplacian variance below which a face is rejected as blurred, measured on the
         * verification decode. Absolute rather than relative: at this point the question
         * is whether the tile will look sharp, not whether it is the best of a bad set.
         */
        private const val MIN_SHARPNESS = 90f
    }

    /** A verified candidate and the crop that verification produced. */
    private data class Verified(
        val shot: DetectedFace,
        val crop: Bitmap,
        val isClean: Boolean,
        val sharpness: Float
    ) {
        val isAcceptable: Boolean get() = isClean && sharpness >= MIN_SHARPNESS
    }

    suspend fun refine(
        videoUri: android.net.Uri,
        clusters: List<PersonCluster>,
        rankedCandidates: (PersonCluster) -> List<DetectedFace>
    ): List<PersonCluster> {
        // One session for every person and every retry, rather than reopening the video
        // for each decode.
        return frameExtractor.withSession(videoUri) { session ->
            clusters.map { cluster -> refineOne(session, cluster, rankedCandidates(cluster)) }
        } ?: clusters
    }

    private suspend fun refineOne(
        session: VideoFrameExtractor.Session,
        cluster: PersonCluster,
        candidates: List<DetectedFace>
    ): PersonCluster {
        var fallback: Verified? = null

        for (candidate in candidates.take(MAX_CANDIDATES)) {
            val verified = verify(session, candidate) ?: continue

            if (verified.isAcceptable) {
                fallback?.crop?.recycle()
                return cluster.withShot(verified)
            }

            // Keep the least-bad attempt in case nothing verifies cleanly.
            if (fallback == null || verified.score > fallback.score) {
                fallback?.crop?.recycle()
                fallback = verified
            } else {
                verified.crop.recycle()
            }
        }

        return fallback?.let { cluster.withShot(it) } ?: cluster
    }

    /** Ranks partial failures so the fallback prefers a clean blurred shot over a crowded one. */
    private val Verified.score: Float
        get() = (if (isClean) 1000f else 0f) + min(sharpness, MIN_SHARPNESS)

    private fun PersonCluster.withShot(verified: Verified): PersonCluster = copy(
        representativeShot = verified.shot.copy(
            generousCropBitmap = verified.crop,
            hasCleanCrop = verified.isClean,
            sharpnessScore = verified.sharpness
        )
    )

    private suspend fun verify(
        session: VideoFrameExtractor.Session,
        shot: DetectedFace
    ): Verified? {
        val originalBox = shot.boundingBox ?: return null
        if (shot.frameWidth <= 0 || shot.frameHeight <= 0) return null

        // Exact seek: verification must judge the frame that was actually chosen, not the
        // nearest keyframe, which may show something else entirely.
        val frame = session.decodeAt(shot.timestampMs, REFINE_FRAME_EDGE, preferSync = false)
            ?: return null

        return try {
            val scale = frame.width.toFloat() / shot.frameWidth
            val scaledBox = originalBox.scaled(scale, frame.width, frame.height)

            val detected = faceDetector.detectFacesInFrame(frame, shot.frameIndex, shot.timestampMs)
            val allBoxes = detected.mapNotNull { it.boundingBox }

            val matched = allBoxes
                .maxByOrNull { iou(it, scaledBox) }
                ?.takeIf { iou(it, scaledBox) >= MIN_MATCH_IOU }
            val targetBox = matched ?: scaledBox

            val neighbours = allBoxes.ifEmpty {
                shot.otherFaceBoxesInFrame.map { it.scaled(scale, frame.width, frame.height) }
            }

            val (crop, plan) = FaceAlignmentHelper.cropPortrait(
                frame = frame,
                box = targetBox,
                otherBoxes = neighbours,
                maxEdge = CROP_MAX_EDGE
            )

            Verified(
                shot = shot,
                crop = crop,
                isClean = plan.isClean,
                sharpness = FaceAlignmentHelper.computeSharpness(frame, targetBox)
            )
        } catch (e: Exception) {
            null
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
