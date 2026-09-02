package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.model.AppearanceSegment
import com.iykyk.assignment.domain.model.DetectedFace
import com.iykyk.assignment.domain.model.PersonCluster

/**
 * Turns the tracklets belonging to one person into counted appearances and picks the shot
 * that represents them.
 */
class AppearanceSegmenter(
    /** Tracklets separated by less than this are treated as one interrupted appearance. */
    private val maxGapMs: Long = 1200L,
    /** Appearances shorter than this are treated as detector noise, not as a moment. */
    private val minSegmentDurationMs: Long = 350L
) {

    /**
     * Builds a person from their tracklets.
     *
     * An appearance is a continuous stretch of screen time. Tracklets already express that
     * directly, so appearances are tracklets, merged across short gaps where the detector
     * simply missed a frame or two.
     */
    fun buildPerson(personId: Int, tracklets: List<Tracklet>): PersonCluster {
        require(tracklets.isNotEmpty()) { "Person $personId has no tracklets" }

        val ordered = tracklets.sortedBy { it.startMs }
        val merged = mutableListOf<MutableList<Tracklet>>()

        for (tracklet in ordered) {
            val current = merged.lastOrNull()
            if (current != null && tracklet.startMs - current.last().endMs <= maxGapMs) {
                current.add(tracklet)
            } else {
                merged.add(mutableListOf(tracklet))
            }
        }

        val allSegments = merged.map { group ->
            val detections = group.flatMap { it.detections }.sortedBy { it.timestampMs }
            AppearanceSegment(
                startTimeMs = detections.first().timestampMs,
                endTimeMs = detections.last().timestampMs,
                detections = detections
            )
        }

        // Drop momentary flickers, but never report a person as having zero appearances.
        val segments = allSegments
            .filter { it.durationMs >= minSegmentDurationMs }
            .ifEmpty { listOf(allSegments.maxByOrNull { it.durationMs } ?: allSegments.first()) }

        val candidates = segments.flatMap { it.detections }
        return PersonCluster(
            id = personId,
            personLabel = "Person $personId",
            appearanceCount = segments.size,
            appearances = segments,
            representativeShot = RepresentativeShotSelector.select(candidates),
            colorIndex = (personId - 1) % 5
        )
    }

    /**
     * Segments a flat list of detections for one person. Kept for callers and tests that
     * work with detections rather than tracklets; timestamps alone decide the boundaries.
     */
    fun segmentPersonAppearances(personId: Int, detections: List<DetectedFace>): PersonCluster {
        require(detections.isNotEmpty()) { "Person $personId has no detections" }

        val sorted = detections.sortedBy { it.timestampMs }
        val runs = mutableListOf<MutableList<DetectedFace>>()

        for (face in sorted) {
            val current = runs.lastOrNull()
            if (current != null && face.timestampMs - current.last().timestampMs <= maxGapMs) {
                current.add(face)
            } else {
                runs.add(mutableListOf(face))
            }
        }

        val tracklets = runs.mapIndexed { index, run -> Tracklet(index, run) }
        return buildPerson(personId, tracklets)
    }
}
