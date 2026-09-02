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
    private val alignedCropSize: Int = 160
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

                // Every on-screen face is kept as crop context, even when it is too small
                // or too clipped to identify, so that neighbour-avoidance sees all of them.
                val onScreenFaces = mlkitFaces.filter { face ->
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

                    // Extract landmarks
                    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                    val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
                    val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
                    val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

                    // Crops
                    val aligned = FaceAlignmentHelper.alignFace(
                        frameBitmap, box, leftEye, rightEye, alignedCropSize
                    )
                    val generousCrop = FaceAlignmentHelper.cropGenerousFace(frameBitmap, box, allBoxesInFrame)

                    // Measured on the native frame pixels, not on the aligned crop: the
                    // aligned crop is resampled to the model input size, so a small face
                    // would be scored on interpolated detail it does not actually have.
                    val sharpness = FaceAlignmentHelper.computeSharpness(frameBitmap, box)

                    val detectedFace = DetectedFace(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        boundingBox = box,
                        frameWidth = frameBitmap.width,
                        frameHeight = frameBitmap.height,
                        alignedCropBitmap = aligned,
                        generousCropBitmap = generousCrop,
                        trackingId = face.trackingId,
                        isSoloShot = isSolo,
                        otherFaceBoxesInFrame = allBoxesInFrame,
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
}
