package com.iykyk.assignment.domain.model

import android.graphics.Bitmap

/**
 * Standardized Target Output returned for every processed video.
 */
data class TargetOutput(
    /** Total count of confirmed unique people across the video. */
    val uniquePeopleCount: Int,
    /** List of unique person IDs (1-based, sorted by total screen presence). */
    val personIdList: List<Int>,
    /** Best representative face portrait crop per person (keyed by person ID). */
    val bestFaceImagePerPerson: Map<Int, Bitmap>,
    /** Total appearance count per person (temporal events, not raw frames). */
    val appearanceCountPerPerson: Map<Int, Int>,
    /** Full appearance timeline containing discrete entry and exit events across all persons. */
    val appearanceTimeline: List<AppearanceTimelineEntry>,
    /** Discrete entry and exit intervals per person. */
    val entryAndExitTimestamps: Map<Int, List<TimeInterval>>,
    /** Final high-resolution collage bitmap ready for sharing or saving. */
    val finalCollageImage: Bitmap?,
    /** Detailed benchmark and profiling telemetry. */
    val processingStatistics: ProcessingStatistics
)

/**
 * Single temporal event in a person's appearance timeline.
 */
data class AppearanceTimelineEntry(
    val personId: Int,
    val segmentIndex: Int,
    val entryTimestampMs: Long,
    val exitTimestampMs: Long,
    val durationMs: Long,
    val peakQualityScore: Float,
    val frameIndices: List<Int>
)

/**
 * Continuous time interval [startMs, endMs].
 */
data class TimeInterval(
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Performance and runtime benchmark telemetry.
 */
data class ProcessingStatistics(
    val totalProcessingTimeMs: Long,
    val videoDurationMs: Long,
    val realtimeFactor: Float, // videoDurationMs / totalProcessingTimeMs (>1.0 means faster than real-time)
    val totalVideoFramesCount: Int,
    val sampledFramesCount: Int,
    val samplingFpsAverage: Float,
    val totalFacesDetected: Int,
    val totalTrackletsBuilt: Int,
    val totalEmbeddingsComputed: Int,
    val sceneCutsDetectedCount: Int,
    val transitionsDetectedCount: Int,
    val peakMemoryUsageMb: Float,
    val faceDetectionTimeMs: Long,
    val embeddingTimeMs: Long,
    val trackingTimeMs: Long,
    val clusteringTimeMs: Long,
    val refinementTimeMs: Long,
    val collageRenderingTimeMs: Long
)
