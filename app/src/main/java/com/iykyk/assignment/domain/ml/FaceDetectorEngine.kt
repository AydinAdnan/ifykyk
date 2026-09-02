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
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .setMinFaceSize(0.12f)
            .build()
        FaceDetection.getClient(options)
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

                val rawValidFaces = mlkitFaces.filter { face ->
                    val box = face.boundingBox
                    box.width() >= 60 && box.height() >= 60 &&
                    box.right > 0 && box.bottom > 0 &&
                    box.left < frameBitmap.width && box.top < frameBitmap.height
                }

                val allBoxesInFrame = rawValidFaces.map { it.boundingBox }
                val isSolo = rawValidFaces.size == 1

                for (face in rawValidFaces) {
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

                    // Junk gate 3: Laplacian sharpness
                    val sharpness = FaceAlignmentHelper.computeSharpness(aligned)

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
