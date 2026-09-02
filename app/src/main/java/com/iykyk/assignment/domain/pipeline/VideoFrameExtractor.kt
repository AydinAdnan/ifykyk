package com.iykyk.assignment.domain.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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

            val targetInterval = (1000f / targetFps).toLong().coerceAtLeast(300L)
            // Limit to max 45 frames for snappy performance while covering full video
            val intervalMs = maxOf(targetInterval, (durationMs / 45L))
            val totalExpectedFrames = max(1, (durationMs / intervalMs).toInt())

            var currentTimestamp = 0L
            var frameIndex = 0

            while (currentTimestamp < durationMs) {
                val timeUs = currentTimestamp * 1000L
                val frameBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    try {
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 720, 1280)
                    } catch (e: Exception) {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    }
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: retriever.getFrameAtTime(timeUs)

                if (frameBitmap != null) {
                    val scaled = scaleDownIfLarge(frameBitmap, maxDim = 720)
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
