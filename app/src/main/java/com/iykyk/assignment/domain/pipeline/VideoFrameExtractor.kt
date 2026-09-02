package com.iykyk.assignment.domain.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class ExtractedFrame(
    val index: Int,
    val timestampMs: Long,
    val bitmap: Bitmap
)

class VideoFrameExtractor(private val context: Context) {

    companion object {
        /**
         * Longest edge for the analysis sweep.
         *
         * 480p was too small: a medium shot yielded a face around 170px and a wide shot
         * far less, below what FaceNet resolves well and below what the detector needs to
         * find secondary people at all. 720p puts a medium shot near 256px while costing
         * roughly half of what 1080p costs to decode and detect on.
         *
         * Tile sharpness is no longer tied to this number: RepresentativeCropRefiner
         * re-decodes the handful of chosen frames at full resolution.
         */
        const val MAX_FRAME_EDGE = 720

        /** Upper bound on decoded frames, to keep memory and latency bounded. */
        const val MAX_FRAMES = 90

        /** Lower bound, so very short clips still get dense sampling. */
        const val MIN_INTERVAL_MS = 250L
    }

    /** Video duration in milliseconds, or null when it cannot be read. */
    suspend fun readDurationMs(videoUri: Uri): Long? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (e: Exception) { /* ignored */ }
        }
    }

    /**
     * Streams frames at approximately [targetFps], capped at [MAX_FRAMES].
     *
     * Frames are handed to [onFrame] one at a time and recycled immediately afterwards.
     * Materialising every frame into a list is not viable at this resolution: 90 frames
     * of 1080x1920 ARGB_8888 is roughly 745 MB, so consumers must extract what they need
     * (crops, embeddings, metadata) inside the callback.
     */
    suspend fun forEachFrame(
        videoUri: Uri,
        targetFps: Float = 3.0f,
        onFrame: suspend (ExtractedFrame, Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var emitted = 0

        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 10_000L

            val targetInterval = (1000f / targetFps).toLong().coerceAtLeast(MIN_INTERVAL_MS)
            val intervalMs = max(targetInterval, durationMs / MAX_FRAMES)
            val totalExpectedFrames = max(1, min(MAX_FRAMES, (durationMs / intervalMs).toInt()))

            var currentTimestamp = 0L
            var frameIndex = 0

            while (currentTimestamp < durationMs && emitted < MAX_FRAMES) {
                val raw = decodeFrame(retriever, currentTimestamp * 1000L)
                if (raw != null) {
                    val bitmap = scaleDownIfLarge(raw, MAX_FRAME_EDGE)
                    try {
                        onFrame(ExtractedFrame(frameIndex, currentTimestamp, bitmap), totalExpectedFrames)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    emitted++
                }
                frameIndex++
                currentTimestamp += intervalMs
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (e: Exception) { /* ignored */ }
        }

        emitted
    }

    /**
     * Decodes a single frame at [timestampMs] at [maxEdge] resolution. Used to re-extract
     * a chosen representative shot at higher fidelity than the analysis pass.
     */
    suspend fun decodeFrameAt(
        videoUri: Uri,
        timestampMs: Long,
        maxEdge: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            decodeFrame(retriever, timestampMs * 1000L)?.let { scaleDownIfLarge(it, maxEdge) }
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (e: Exception) { /* ignored */ }
        }
    }

    private fun decodeFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                // getScaledFrameAtTime letterboxes into the given box, so pass a square
                // bound and let scaleDownIfLarge do the final aspect-correct resize.
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    MAX_FRAME_EDGE,
                    MAX_FRAME_EDGE
                )?.let { return it }
            } catch (e: Exception) {
                // fall through to the unscaled path
            }
        }
        return try {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(timeUs)
        } catch (e: Exception) {
            null
        }
    }

    private fun scaleDownIfLarge(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (max(w, h) <= maxDim) return bitmap

        val scale = maxDim.toFloat() / max(w, h)
        val newW = max(1, (w * scale).toInt())
        val newH = max(1, (h * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }
}
