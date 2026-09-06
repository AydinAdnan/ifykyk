package com.iykyk.assignment.domain.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.iykyk.assignment.domain.model.DetectedFace
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class FaceDetectorEngine(
    /** Edge length of the aligned crop, dictated by the recognition model input. */
    private val alignedCropSize: Int = 160,
    /**
     * Longest edge of the portrait crop retained per detection.
     *
     * Deliberately small. A crop is kept for every detection in the video, so a
     * presentation-sized one would cost megabytes each and exhaust memory long before the
     * sweep finished. Only the chosen shot is ever displayed, and
     * RepresentativeCropRefiner re-cuts that one at full resolution.
     */
    private val portraitCropMaxEdge: Int = 320
) {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            // ACCURATE, not FAST. Missing a face is the expensive error here: an
            // undetected second person is both an uncounted appearance and a neighbour
            // that the crop logic cannot know to avoid, which is how other people ended
            // up inside someone else portrait tile.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .setMinFaceSize(MIN_RELATIVE_FACE_SIZE)
            .build()
        FaceDetection.getClient(options)
    }

    companion object {
        /** Smallest face, as a fraction of the frame width, the detector will look for. */
        const val MIN_RELATIVE_FACE_SIZE = 0.06f

        /**
         * Faces narrower than this fraction of the frame width are recorded as context
         * (so crops can avoid them) but are too small to identify or to present.
         */
        const val MIN_USABLE_RELATIVE_WIDTH = 0.045f

        /** Minimum Laplacian sharpness score required to reject motion blur & artifacts */
        const val MIN_DETECTION_SHARPNESS = 25f
    }

    /**
     * Detects faces in a bitmap frame and applies quality / junk gate filtering.
     */
    suspend fun detectFacesInFrame(
        frameBitmap: Bitmap,
        frameIndex: Int,
        timestampMs: Long
    ): List<DetectedFace> = suspendCancellableCoroutine { continuation ->
        val inputImage = InputImage.fromBitmap(frameBitmap, 0)
        detector.process(inputImage)
            .addOnSuccessListener { mlkitFaces ->
                val validFaces = mutableListOf<DetectedFace>()

                // Suppress duplicate overlapping detections of the same face in this frame
                val nmsFaces = applyNms(mlkitFaces)

                // Every on-screen face is kept as crop context, even when it is too small
                // or too clipped to identify, so that neighbour-avoidance sees all of them.
                val onScreenFaces = nmsFaces.filter { face ->
                    val box = face.boundingBox
                    box.width() > 0 && box.height() > 0 &&
                        box.right > 0 && box.bottom > 0 &&
                        box.left < frameBitmap.width && box.top < frameBitmap.height
                }

                val allBoxesInFrame = onScreenFaces.map { it.boundingBox }
                val minUsableWidth = frameBitmap.width * MIN_USABLE_RELATIVE_WIDTH
                val identifiableFaces = onScreenFaces.filter { it.boundingBox.width() >= minUsableWidth }
                val isSolo = onScreenFaces.size == 1

                for (face in identifiableFaces) {
                    val box = face.boundingBox
                    val otherBoxes = allBoxesInFrame.filter { it != box }

                    // Measured on the native frame pixels, not on the aligned crop: the
                    // aligned crop is resampled to the model input size, so a small face
                    // would be scored on interpolated detail it does not actually have.
                    val sharpness = FaceAlignmentHelper.computeSharpness(frameBitmap, box)
                    if (sharpness < MIN_DETECTION_SHARPNESS) {
                        continue // Reject motion blur and noise
                    }

                    // Extract landmarks
                    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                    val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
                    val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
                    val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

                    // Crops
                    val aligned = FaceAlignmentHelper.alignFace(
                        frameBitmap, box, leftEye, rightEye, alignedCropSize, headEulerZ = face.headEulerAngleZ
                    )
                    val (portraitCrop, cropPlan) =
                        FaceAlignmentHelper.cropPortrait(
                            frameBitmap, box, otherBoxes, portraitCropMaxEdge
                        )

                    val detectedFace = DetectedFace(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        boundingBox = box,
                        frameWidth = frameBitmap.width,
                        frameHeight = frameBitmap.height,
                        alignedCropBitmap = aligned,
                        generousCropBitmap = portraitCrop,
                        hasCleanCrop = cropPlan.isClean,
                        trackingId = face.trackingId,
                        isSoloShot = isSolo && otherBoxes.isEmpty(),
                        otherFaceBoxesInFrame = otherBoxes,
                        leftEye = leftEye,
                        rightEye = rightEye,
                        nose = nose,
                        mouthLeft = mouthLeft,
                        mouthRight = mouthRight,
                        headEulerY = face.headEulerAngleY,
                        headEulerZ = face.headEulerAngleZ,
                        smilingProbability = face.smilingProbability ?: 0f,
                        leftEyeOpenProbability = face.leftEyeOpenProbability ?: 0.5f,
                        rightEyeOpenProbability = face.rightEyeOpenProbability ?: 0.5f,
                        sharpnessScore = sharpness
                    )
                    validFaces.add(detectedFace)
                }

                if (continuation.isActive) {
                    continuation.resume(validFaces)
                }
            }
            .addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resume(emptyList())
                }
            }
    }

    private fun applyNms(faces: List<Face>): List<Face> {
        if (faces.size <= 1) return faces
        val sorted = faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
        val kept = mutableListOf<Face>()
        for (candidate in sorted) {
            val candBox = candidate.boundingBox
            val isDuplicate = kept.any { existing ->
                boxOverlapRatio(candBox, existing.boundingBox) > 0.40f
            }
            if (!isDuplicate) {
                kept.add(candidate)
            }
        }
        return kept
    }

    private fun boxOverlapRatio(a: android.graphics.Rect, b: android.graphics.Rect): Float {
        val interLeft = kotlin.math.max(a.left, b.left)
        val interTop = kotlin.math.max(a.top, b.top)
        val interRight = kotlin.math.min(a.right, b.right)
        val interBottom = kotlin.math.min(a.bottom, b.bottom)
        val interW = interRight - interLeft
        val interH = interBottom - interTop
        if (interW <= 0 || interH <= 0) return 0f
        val interArea = interW.toFloat() * interH
        val minArea = kotlin.math.min(a.width() * a.height(), b.width() * b.height()).toFloat()
        return if (minArea > 0f) interArea / minArea else 0f
    }
}

