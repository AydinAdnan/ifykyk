package com.iykyk.assignment.domain.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class ExtractedFrame(
    val index: Int,
    val timestampMs: Long,
    val bitmap: Bitmap
)

/** Outcome of a sampling sweep. */
data class SweepResult(
    val framesDelivered: Int,
    val durationMs: Long,
    val lastTimestampMs: Long
)

class VideoFrameExtractor(private val context: Context) {

    companion object {
        /**
         * Longest edge for the analysis sweep.
         * 480p gives optimal decoding speed on mobile while providing sharp detail
         * for ML Kit face detection and 5-point landmark canonical alignment.
         */
        const val MAX_FRAME_EDGE = 480

        /** Upper bound on frames handed to detection, to keep latency under 1 minute. */
        const val MAX_FRAMES = 28

        /** Lower bound on sampling interval. */
        const val MIN_INTERVAL_MS = 400L
    }

    /**
     * An open handle on one video.
     */
    class Session internal constructor(
        private val retriever: MediaMetadataRetriever,
        val durationMs: Long
    ) {
        val videoWidth: Int
        val videoHeight: Int

        init {
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                videoWidth = rawH
                videoHeight = rawW
            } else {
                videoWidth = rawW
                videoHeight = rawH
            }
        }

        fun decodeAt(timestampMs: Long, maxEdge: Int, preferSync: Boolean): Bitmap? {
            val timeUs = timestampMs * 1000L
            val option = if (preferSync) {
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            } else {
                MediaMetadataRetriever.OPTION_CLOSEST
            }

            val maxDim = max(videoWidth, videoHeight).coerceAtLeast(1)
            val scale = maxEdge.toFloat() / maxDim
            val targetW = (((videoWidth * scale).toInt() / 2) * 2).coerceAtLeast(16)
            val targetH = (((videoHeight * scale).toInt() / 2) * 2).coerceAtLeast(16)

            val raw = decodeScaled(timeUs, option, targetW, targetH)
                ?: decodeScaled(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetW, targetH)
                ?: return null

            return scaleDownIfLarge(raw, maxEdge)
        }

        private fun decodeScaled(timeUs: Long, option: Int, dstWidth: Int, dstHeight: Int): Bitmap? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    retriever.getScaledFrameAtTime(timeUs, option, dstWidth, dstHeight)
                        ?.let { return it }
                } catch (e: Exception) {
                    // fall through to the unscaled path
                }
            }
            return try {
                retriever.getFrameAtTime(timeUs, option)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Opens a session, runs [block], and always releases the retriever. */
    suspend fun <T> withSession(videoUri: Uri, block: suspend (Session) -> T): T? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            var pfd: ParcelFileDescriptor? = null
            var tempFile: File? = null
            try {
                var dataSourceSet = false
                try {
                    pfd = context.contentResolver.openFileDescriptor(videoUri, "r")
                    if (pfd != null) {
                        retriever.setDataSource(pfd.fileDescriptor)
                        dataSourceSet = true
                    }
                } catch (e: Exception) {
                    // Fall back to context URI or cached file
                }

                if (!dataSourceSet) {
                    try {
                        retriever.setDataSource(context, videoUri)
                        dataSourceSet = true
                    } catch (e: Exception) {
                        // Fall back to copying to cache
                    }
                }

                if (!dataSourceSet) {
                    val cached = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(videoUri)?.use { input ->
                        cached.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile = cached
                    retriever.setDataSource(cached.absolutePath)
                }

                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 10_000L
                block(Session(retriever, durationMs))
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                try { retriever.release() } catch (e: Exception) { /* ignored */ }
                try { pfd?.close() } catch (e: Exception) { /* ignored */ }
                try { tempFile?.delete() } catch (e: Exception) { /* ignored */ }
            }
        }

    /**
     * Streams sampled frames at approximately [targetFps], capped at [MAX_FRAMES].
     *
     * Frames are handed to [onFrame] one at a time and recycled immediately afterwards:
     * materialising a whole sweep would hold hundreds of megabytes of bitmaps at once.
     *
     * Sampling decodes to exact timestamps. Snapping to keyframes is far cheaper, but it
     * silently destroys temporal coverage: with keyframes a couple of seconds apart, most
     * requested times return the frame their neighbours already returned, so the real
     * sample count collapses to roughly the keyframe count. Anyone on screen briefly - or
     * only during the camera movement between two settled shots - then falls into the gap
     * and is never seen at all. Recall decides who appears in the collage, so it wins over
     * decode cost here.
     */
    suspend fun forEachFrame(
        videoUri: Uri,
        targetFps: Float = 3.0f,
        onFrame: suspend (ExtractedFrame, Int) -> Unit
    ): SweepResult = withSession(videoUri) { session ->
        val durationMs = session.durationMs
        val targetInterval = (1000f / targetFps).toLong().coerceAtLeast(MIN_INTERVAL_MS)
        val intervalMs = max(targetInterval, durationMs / MAX_FRAMES)
        val expectedTotal = max(1, min(MAX_FRAMES, (durationMs / intervalMs).toInt()))

        var timestamp = 0L
        var delivered = 0
        var lastTimestamp = 0L

        while (timestamp < durationMs && delivered < MAX_FRAMES) {
            val bitmap = session.decodeAt(timestamp, MAX_FRAME_EDGE, preferSync = false)
            if (bitmap != null) {
                lastTimestamp = timestamp
                try {
                    onFrame(ExtractedFrame(delivered, timestamp, bitmap), expectedTotal)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                delivered++
            }
            timestamp += intervalMs
        }

        SweepResult(delivered, durationMs, lastTimestamp)
    } ?: SweepResult(0, 0L, 0L)
}

private fun scaleDownIfLarge(bitmap: Bitmap, maxDim: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (max(w, h) <= maxDim) return bitmap

    val scale = maxDim.toFloat() / max(w, h)
    val scaled = Bitmap.createScaledBitmap(
        bitmap,
        max(1, (w * scale).toInt()),
        max(1, (h * scale).toInt()),
        true
    )
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}
