package com.iykyk.assignment.domain.pipeline

import android.content.Context
import android.net.Uri
import com.iykyk.assignment.domain.ml.FaceDetectorEngine
import com.iykyk.assignment.domain.ml.TFLiteFaceEmbedder
import com.iykyk.assignment.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

class VideoPipelineEngine(private val context: Context) {

    private val frameExtractor = VideoFrameExtractor(context)
    private val faceDetector = FaceDetectorEngine()
    private val faceEmbedder = TFLiteFaceEmbedder(context)
    private val clusterer = AgglomerativeClusterer(faceEmbedder, similarityThreshold = 0.46f, centroidMergeThreshold = 0.52f)
    private val segmenter = AppearanceSegmenter(maxGapMs = 1200L, minSegmentDurationMs = 350L)
    private val canvasRenderer = CollageCanvasRenderer(context)

    // channelFlow (not flow) because frame decoding runs on Dispatchers.IO and progress is
    // reported from inside that callback; a plain flow builder forbids cross-context emission.
    fun processVideo(videoUri: Uri): Flow<PipelineProgress> = channelFlow {
        val completedSteps = mutableSetOf<PipelineStep>()

        // 1. EXTRACT FRAMES
        send(
            PipelineProgress(
                currentStep = PipelineStep.EXTRACT_FRAMES,
                progressPercent = 10,
                statusMessage = "Sampling video frames...",
                completedSteps = completedSteps
            )
        )

        val durationMs = frameExtractor.readDurationMs(videoUri) ?: 0L
        val allDetectedFaces = mutableListOf<DetectedFace>()
        var previewBitmap: android.graphics.Bitmap? = null
        var bestSharpness = 0f
        var lastEmittedPct = 10
        var framesSeen = 0
        var lastTimestampMs = 0L

        // Frames are streamed and recycled one at a time rather than collected into a
        // list: at 1080p a full sampling pass would otherwise hold hundreds of megabytes
        // of bitmaps alive at once.
        frameExtractor.forEachFrame(videoUri, targetFps = 3.0f) { frame, expectedTotal ->
            framesSeen++
            lastTimestampMs = frame.timestampMs

            val faces = faceDetector.detectFacesInFrame(frame.bitmap, frame.index, frame.timestampMs)
            for (face in faces) {
                allDetectedFaces.add(face)
                if (face.sharpnessScore > bestSharpness && face.generousCropBitmap != null) {
                    bestSharpness = face.sharpnessScore
                    previewBitmap = face.generousCropBitmap
                }
            }

            val pct = 10 + (framesSeen * 35 / expectedTotal.coerceAtLeast(1)).coerceAtMost(35)
            if (pct >= lastEmittedPct + 4) {
                lastEmittedPct = pct
                send(
                    PipelineProgress(
                        currentStep = PipelineStep.DETECT_FACES,
                        progressPercent = pct,
                        currentFaceBitmap = previewBitmap,
                        statusMessage = "Found ${allDetectedFaces.size} face detections...",
                        completedSteps = completedSteps
                    )
                )
            }
        }

        completedSteps.add(PipelineStep.EXTRACT_FRAMES)
        completedSteps.add(PipelineStep.DETECT_FACES)

        if (framesSeen == 0) {
            send(
                PipelineProgress(
                    currentStep = PipelineStep.EXTRACT_FRAMES,
                    progressPercent = 0,
                    statusMessage = "No frames extracted from video",
                    completedSteps = completedSteps,
                    isFinished = true,
                    error = "Could not decode video frames."
                )
            )
            return@channelFlow
        }

        if (allDetectedFaces.isEmpty()) {
            send(
                PipelineProgress(
                    currentStep = PipelineStep.DETECT_FACES,
                    progressPercent = 0,
                    statusMessage = "No faces found in video",
                    completedSteps = completedSteps,
                    isFinished = true,
                    error = "No faces detected in this video."
                )
            )
            return@channelFlow
        }

        // 3. GENERATE EMBEDDINGS
        send(
            PipelineProgress(
                currentStep = PipelineStep.GENERATE_EMBEDDINGS,
                progressPercent = 50,
                currentFaceBitmap = previewBitmap,
                statusMessage = "Extracting 512-d face feature embeddings...",
                completedSteps = completedSteps
            )
        )

        val facesWithEmbeddings = allDetectedFaces.mapIndexed { idx, face ->
            val embedding = faceEmbedder.getEmbedding(face.alignedCropBitmap)
            face.copy(embedding = embedding)
        }
        completedSteps.add(PipelineStep.GENERATE_EMBEDDINGS)

        // 4. CLUSTER PEOPLE
        send(
            PipelineProgress(
                currentStep = PipelineStep.CLUSTER_PEOPLE,
                progressPercent = 68,
                currentFaceBitmap = previewBitmap,
                statusMessage = "Clustering unique individuals...",
                completedSteps = completedSteps
            )
        )

        val clusterMap = clusterer.clusterFaces(facesWithEmbeddings)
        completedSteps.add(PipelineStep.CLUSTER_PEOPLE)

        // 5. COUNT APPEARANCES & SELECT BEST SHOTS
        send(
            PipelineProgress(
                currentStep = PipelineStep.COUNT_APPEARANCES,
                progressPercent = 82,
                currentFaceBitmap = previewBitmap,
                statusMessage = "Analyzing appearance segments & representative shots...",
                completedSteps = completedSteps
            )
        )

        val personClusters = clusterMap.map { (personId, faceList) ->
            segmenter.segmentPersonAppearances(personId, faceList)
        }
        completedSteps.add(PipelineStep.COUNT_APPEARANCES)
        completedSteps.add(PipelineStep.SELECT_BEST_SHOTS)

        // 6. CREATE COLLAGE
        send(
            PipelineProgress(
                currentStep = PipelineStep.CREATE_COLLAGE,
                progressPercent = 95,
                currentFaceBitmap = personClusters.firstOrNull()?.representativeShot?.generousCropBitmap,
                statusMessage = "Rendering scrapbook stamp collage...",
                completedSteps = completedSteps
            )
        )

        val collageBitmap = canvasRenderer.renderCollageBitmap(personClusters)
        completedSteps.add(PipelineStep.CREATE_COLLAGE)

        val totalAppearances = personClusters.sumOf { it.appearanceCount }
        val finalAnalysis = AnalysisResult(
            videoUri = videoUri.toString(),
            videoDurationMs = if (durationMs > 0L) durationMs else lastTimestampMs,
            totalUniquePeople = personClusters.size,
            totalAppearances = totalAppearances,
            clusters = personClusters,
            collageBitmap = collageBitmap
        )

        // FINISHED
        send(
            PipelineProgress(
                currentStep = PipelineStep.CREATE_COLLAGE,
                progressPercent = 100,
                currentFaceBitmap = personClusters.firstOrNull()?.representativeShot?.generousCropBitmap,
                statusMessage = "Complete! Found ${personClusters.size} unique people.",
                completedSteps = completedSteps,
                isFinished = true,
                finalResult = finalAnalysis
            )
        )
    }.flowOn(Dispatchers.Default)

    suspend fun getFinalAnalysisResult(
        videoUri: Uri,
        clusters: List<PersonCluster>,
        durationMs: Long
    ): AnalysisResult {
        val totalAppearances = clusters.sumOf { it.appearanceCount }
        val collageBitmap = canvasRenderer.renderCollageBitmap(clusters)
        return AnalysisResult(
            videoUri = videoUri.toString(),
            videoDurationMs = durationMs,
            totalUniquePeople = clusters.size,
            totalAppearances = totalAppearances,
            clusters = clusters,
            collageBitmap = collageBitmap
        )
    }
}
