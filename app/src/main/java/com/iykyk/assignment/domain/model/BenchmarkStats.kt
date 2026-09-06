package com.iykyk.assignment.domain.model

/**
 * High-precision benchmark recorder for mobile CV pipeline telemetry.
 */
class BenchmarkStats {

    private var startTimeMs: Long = 0L
    private var endTimeMs: Long = 0L

    var detectionTimeMs: Long = 0L
    var trackingTimeMs: Long = 0L
    var embeddingTimeMs: Long = 0L
    var clusteringTimeMs: Long = 0L
    var refinementTimeMs: Long = 0L
    var collageTimeMs: Long = 0L

    var totalVideoFramesCount: Int = 0
    var sampledFramesCount: Int = 0
    var totalFacesDetected: Int = 0
    var totalTrackletsBuilt: Int = 0
    var totalEmbeddingsComputed: Int = 0
    var sceneCutsCount: Int = 0
    var transitionsCount: Int = 0

    fun start() {
        startTimeMs = System.currentTimeMillis()
    }

    fun finish() {
        endTimeMs = System.currentTimeMillis()
    }

    fun getPeakMemoryMb(): Float {
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        return (usedBytes / (1024f * 1024f))
    }

    fun buildSummary(videoDurationMs: Long): ProcessingStatistics {
        val elapsed = (endTimeMs - startTimeMs).coerceAtLeast(1L)
        val realtimeFactor = if (elapsed > 0) videoDurationMs.toFloat() / elapsed.toFloat() else 1.0f
        val samplingFps = if (elapsed > 0) (sampledFramesCount.toFloat() / (elapsed / 1000f)) else 0f

        return ProcessingStatistics(
            totalProcessingTimeMs = elapsed,
            videoDurationMs = videoDurationMs,
            realtimeFactor = realtimeFactor,
            totalVideoFramesCount = totalVideoFramesCount,
            sampledFramesCount = sampledFramesCount,
            samplingFpsAverage = samplingFps,
            totalFacesDetected = totalFacesDetected,
            totalTrackletsBuilt = totalTrackletsBuilt,
            totalEmbeddingsComputed = totalEmbeddingsComputed,
            sceneCutsDetectedCount = sceneCutsCount,
            transitionsDetectedCount = transitionsCount,
            peakMemoryUsageMb = getPeakMemoryMb(),
            faceDetectionTimeMs = detectionTimeMs,
            embeddingTimeMs = embeddingTimeMs,
            trackingTimeMs = trackingTimeMs,
            clusteringTimeMs = clusteringTimeMs,
            refinementTimeMs = refinementTimeMs,
            collageRenderingTimeMs = collageTimeMs
        )
    }
}
