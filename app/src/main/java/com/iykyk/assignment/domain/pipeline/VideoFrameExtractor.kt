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
         *
         * 480p was too small: a medium shot yielded a face around 170px and a wide shot
         * far less, below what FaceNet resolves well and below what the detector needs to
         * find secondary people at all. 720p puts a medium shot near 256px while costing
         * roughly half of what 1080p costs to decode and detect on.
         *
         * Tile sharpness is not tied to this number: RepresentativeCropRefiner re-decodes
         * the handful of chosen frames at higher resolution.
         */
        const val MAX_FRAME_EDGE = 720

        /** Upper bound on frames handed to detection, to keep latency bounded. */
        const val MAX_FRAMES = 48

        /** Lower bound, so very short clips still get dense sampling. */
        const val MIN_INTERVAL_MS = 300L

        /**
         * Below this many distinct frames, keyframe-only sampling is considered to have
         * collapsed and the sweep is retried decoding to exact timestamps.
         */
        private const val MIN_DISTINCT_FRAMES = 8
    }

    /**
     * An open handle on one video.
     *
     * MediaMetadataRetriever.setDataSource parses the container and is far from free, and
     * the pipeline used to pay for it repeatedly: once to read the duration, once for the
     * sweep, and once more per person during representative refinement. A session pays it
     * once and hands out as many decodes as the caller needs.
     */
    class Session internal constructor(
        private val retriever: MediaMetadataRetriever,
        val durationMs: Long
    ) {
        /**
         * @param preferSync seek to the nearest keyframe rather than decoding forward to
         *   the exact timestamp. Keyframe seeks are dramatically cheaper - an exact seek
         *   must decode every frame from the preceding keyframe to the target, so a sweep
         *   of N samples can decode far more frames than the video even contains.
         */
        fun decodeAt(timestampMs: Long, maxEdge: Int, preferSync: Boolean): Bitmap? {
            val timeUs = timestampMs * 1000L
            val option = if (preferSync) {
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            } else {
                MediaMetadataRetriever.OPTION_CLOSEST
            }

            val raw = decodeScaled(timeUs, option, maxEdge)
                ?: decodeScaled(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdge)
                ?: return null

            return scaleDownIfLarge(raw, maxEdge)
        }

        private fun decodeScaled(timeUs: Long, option: Int, maxEdge: Int): Bitmap? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    retriever.getScaledFrameAtTime(timeUs, option, maxEdge, maxEdge)
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
            try {
                retriever.setDataSource(context, videoUri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 10_000L
                block(Session(retriever, durationMs))
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                try { retriever.release() } catch (e: Exception) { /* ignored */ }
            }
        }

    /**
     * Streams sampled frames at approximately [targetFps], capped at [MAX_FRAMES].
     *
     * Frames are handed to [onFrame] one at a time and recycled immediately afterwards:
     * materialising a whole sweep would hold hundreds of megabytes of bitmaps at once.
     *
     * Sampling snaps to keyframes, which is far cheaper than decoding forward to exact
     * timestamps. Because several requested times can then land on the same frame, and
     * because a static shot repeats regardless, near-identical frames are dropped before
     * they reach [onFrame] - detection on them costs full price and adds nothing. If a
     * video has keyframes so sparse that this leaves too little to work with, the sweep is
     * retried decoding to exact timestamps.
     */
    suspend fun forEachFrame(
        videoUri: Uri,
        targetFps: Float = 2.0f,
        onFrame: suspend (ExtractedFrame, Int) -> Unit
    ): SweepResult = withSession(videoUri) { session ->
        val fast = sweep(session, targetFps, preferSync = true, onFrame = onFrame)
        if (fast.framesDelivered >= MIN_DISTINCT_FRAMES || session.durationMs < 4_000L) {
            fast
        } else {
            sweep(session, targetFps, preferSync = false, onFrame = onFrame)
        }
    } ?: SweepResult(0, 0L, 0L)

    private suspend fun sweep(
        session: Session,
        targetFps: Float,
        preferSync: Boolean,
        onFrame: suspend (ExtractedFrame, Int) -> Unit
    ): SweepResult {
        val durationMs = session.durationMs
        val targetInterval = (1000f / targetFps).toLong().coerceAtLeast(MIN_INTERVAL_MS)
        val intervalMs = max(targetInterval, durationMs / MAX_FRAMES)
        val expectedTotal = max(1, min(MAX_FRAMES, (durationMs / intervalMs).toInt()))

        var timestamp = 0L
        var delivered = 0
        var lastTimestamp = 0L
        var previousHash: Long? = null

        while (timestamp < durationMs && delivered < MAX_FRAMES) {
            val bitmap = session.decodeAt(timestamp, MAX_FRAME_EDGE, preferSync)
            if (bitmap != null) {
                val hash = averageHash(bitmap)
                val duplicate = previousHash?.let { FrameHash.isDuplicate(it, hash) } == true

                if (duplicate) {
                    bitmap.recycle()
                } else {
                    previousHash = hash
                    lastTimestamp = timestamp
                    try {
                        onFrame(ExtractedFrame(delivered, timestamp, bitmap), expectedTotal)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    delivered++
                }
            }
            timestamp += intervalMs
        }

        return SweepResult(delivered, durationMs, lastTimestamp)
    }

    /** Reduces a frame to an 8x8 luminance grid and hashes it. */
    private fun averageHash(bitmap: Bitmap): Long {
        val grid = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        grid.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        if (grid !== bitmap) grid.recycle()

        val luma = FloatArray(64) { i ->
            val c = pixels[i]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        return FrameHash.ofLumaGrid(luma)
    }
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
