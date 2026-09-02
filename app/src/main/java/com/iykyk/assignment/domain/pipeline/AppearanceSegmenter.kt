package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.model.AppearanceSegment
import com.iykyk.assignment.domain.model.DetectedFace
import com.iykyk.assignment.domain.model.PersonCluster

class AppearanceSegmenter(
    private val maxGapMs: Long = 1200L,
    private val minSegmentDurationMs: Long = 350L
) {

    /**
     * Segments a cluster of face detections into continuous appearance periods
     * and chooses the single best representative shot.
     */
    fun segmentPersonAppearances(
        personId: Int,
        detections: List<DetectedFace>
    ): PersonCluster {
        if (detections.isEmpty()) {
            throw IllegalArgumentException("Detections cannot be empty for person ")
        }

        // Sort by timestamp ascending
        val sorted = detections.sortedBy { it.timestampMs }
        val rawSegments = mutableListOf<MutableList<DetectedFace>>()
        var currentSegment = mutableListOf<DetectedFace>()

        for (face in sorted) {
            if (currentSegment.isEmpty()) {
                currentSegment.add(face)
            } else {
                val lastFace = currentSegment.last()
                val gap = face.timestampMs - lastFace.timestampMs

                // Tracking ID break or time gap break
                val trackingChanged = (face.trackingId != null && lastFace.trackingId != null && face.trackingId != lastFace.trackingId)
                if (gap > maxGapMs || (gap > 600L && trackingChanged)) {
                    rawSegments.add(currentSegment)
                    currentSegment = mutableListOf(face)
                } else {
                    currentSegment.add(face)
                }
            }
        }
        if (currentSegment.isNotEmpty()) {
            rawSegments.add(currentSegment)
        }

        // Filter out whip-pan noise segments (unless it's the only segment)
        val validSegments = rawSegments.map { faceList ->
            val start = faceList.first().timestampMs
            val end = faceList.last().timestampMs
            AppearanceSegment(start, end, faceList)
        }.filter { segment ->
            segment.durationMs >= minSegmentDurationMs || rawSegments.size == 1
        }.ifEmpty {
            // If all were strictly short, keep the longest one
            val longest = rawSegments.maxByOrNull { it.last().timestampMs - it.first().timestampMs } ?: rawSegments.first()
            listOf(AppearanceSegment(longest.first().timestampMs, longest.last().timestampMs, longest))
        }

        // Choose the single overall best representative shot across all appearances:
        // 1. Strict Solo Preference: Never pick a group crop if a solo portrait exists
        // 2. Strict Sharpness Preference: Never pick motion-blurred faces
        // 3. Score Frontality, Smile, and Eyes Open
        val allFaces = validSegments.flatMap { it.detections }
        val soloFaces = allFaces.filter { it.isSoloShot && it.otherFaceBoxesInFrame.size <= 1 }.ifEmpty { allFaces }
        val nonBlurFaces = soloFaces.filter { it.sharpnessScore >= 80f }.ifEmpty {
            soloFaces.filter { it.sharpnessScore >= 50f }.ifEmpty { soloFaces }
        }
        val bestShot = nonBlurFaces.maxByOrNull { it.repScore } ?: soloFaces.maxByOrNull { it.repScore } ?: allFaces.first()

        return PersonCluster(
            id = personId,
            personLabel = "Person $personId",
            appearanceCount = validSegments.size,
            appearances = validSegments,
            representativeShot = bestShot,
            colorIndex = (personId - 1) % 5
        )
    }
}
