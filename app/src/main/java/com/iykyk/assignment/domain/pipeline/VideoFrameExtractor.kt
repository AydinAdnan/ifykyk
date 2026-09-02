package com.iykyk.assignment.domain.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

data class ExtractedFrame(
    val index: Int,
    val timestampMs: Long,
    val bitmap: Bitmap
)

class VideoFrameExtractor(private val context: Context) {

    /**
     * Extracts frames from video URI at target FPS (default 2 FPS).
     */
    suspend fun extractFrames(
        videoUri: Uri,
        targetFps: Float = 3.0f,
        onProgress: (Int, Int) -> Unit
    ): List<ExtractedFrame> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<ExtractedFrame>()

        try {
            retriever.setDataSource(context, videoUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 10000L

            val intervalMs = (1000f / targetFps).toLong().coerceAtLeast(250L)
            val totalExpectedFrames = max(1, (durationMs / intervalMs).toInt())

            var currentTimestamp = 0L
            var frameIndex = 0

            while (currentTimestamp < durationMs) {
                // Seek to microsecond timestamp with OPTION_CLOSEST for exact frame accuracy
                val frameBitmap = retriever.getFrameAtTime(
                    currentTimestamp * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (frameBitmap != null) {
                    // Downscale for detection performance if needed (max dimension 960px)
                    val scaled = scaleDownIfLarge(frameBitmap, maxDim = 960)
                    frames.add(
                        ExtractedFrame(
                            index = frameIndex,
                            timestampMs = currentTimestamp,
                            bitmap = scaled
                        )
                    )
                }

                frameIndex++
                currentTimestamp += intervalMs
                onProgress(frameIndex, totalExpectedFrames)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignored
            }
        }

        frames
    }

    private fun scaleDownIfLarge(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap

        val scale = maxDim.toFloat() / max(w, h)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
