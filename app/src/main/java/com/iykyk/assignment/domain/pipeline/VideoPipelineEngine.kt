package com.iykyk.assignment.domain.pipeline

import android.content.Context
import android.net.Uri
import com.iykyk.assignment.domain.model.PipelineProgress
import kotlinx.coroutines.flow.Flow

/**
 * Production Video Pipeline Entry Point.
 * Delegates video face-indexing, tracking, appearance clustering, and scrapbook collage
 * generation to the high-performance ProductionVideoPipeline.
 */
class VideoPipelineEngine(private val context: Context) {

    private val productionPipeline = ProductionVideoPipeline(context)

    fun processVideo(videoUri: Uri): Flow<PipelineProgress> {
        return productionPipeline.processVideo(videoUri)
    }
}

