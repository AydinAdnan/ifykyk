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
    private val faceEmbedder = TFLiteFaceEmbedder(context)
    private val faceDetector = FaceDetectorEngine(alignedCropSize = faceEmbedder.inputSize)
    private val trackletBuilder = TrackletBuilder(faceEmbedder)
    private val trackletEmbedder = TrackletEmbedder(faceEmbedder)
    private val clusterer = AgglomerativeClusterer(faceEmbedder)
    private val segmenter = AppearanceSegmenter(maxGapMs = 1200L, minSegmentDurationMs = 350L)
    private val cropRefiner = RepresentativeCropRefiner(frameExtractor, faceDetector)
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

        val allDetectedFaces = mutableListOf<DetectedFace>()
        var previewBitmap: android.graphics.Bitmap? = null
        var bestSharpness = 0f
        var lastEmittedPct = 10
        var framesSeen = 0

        // Frames are streamed and recycled one at a time rather than collected into a
        // list: at 1080p a full sampling pass would otherwise hold hundreds of megabytes
        // of bitmaps alive at once.
        val sweep = frameExtractor.forEachFrame(videoUri) { frame, expectedTotal ->
            framesSeen++

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

        if (sweep.framesDelivered == 0) {
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

        // 3. TRACK, THEN EMBED
        //
        // Order matters for cost. Detections that sit in the same place in adjacent frames
        // are the same person by geometry alone, so grouping first means the recognition
        // model runs a few times per track instead of once per detection.
        val tracklets = trackletBuilder.build(allDetectedFaces)

        send(
            PipelineProgress(
                currentStep = PipelineStep.GENERATE_EMBEDDINGS,
                progressPercent = 50,
                currentFaceBitmap = previewBitmap,
                statusMessage = "Identifying ${tracklets.size} tracked appearances...",
                completedSteps = completedSteps
            )
        )

        var lastEmbedPct = 50
        val trackletEmbeddings = trackletEmbedder.embedAll(tracklets) { done, total ->
            val pct = 50 + (done * 20 / total.coerceAtLeast(1))
            if (pct >= lastEmbedPct + 3) {
                lastEmbedPct = pct
                send(
                    PipelineProgress(
                        currentStep = PipelineStep.GENERATE_EMBEDDINGS,
                        progressPercent = pct,
                        currentFaceBitmap = previewBitmap,
                        statusMessage = "Extracting face features ($done/$total)...",
                        completedSteps = completedSteps
                    )
                )
            }
        }
        completedSteps.add(PipelineStep.GENERATE_EMBEDDINGS)

        // 4. CLUSTER PEOPLE
        send(
            PipelineProgress(
                currentStep = PipelineStep.CLUSTER_PEOPLE,
                progressPercent = 72,
                currentFaceBitmap = previewBitmap,
                statusMessage = "Clustering unique individuals...",
                completedSteps = completedSteps
            )
        )

        val clusterMap = clusterer.clusterTracklets(tracklets, trackletEmbeddings)
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

        val draftClusters = clusterMap.map { (personId, personTracklets) ->
            segmenter.buildPerson(personId, personTracklets)
        }

        // Only one frame per person is ever shown, so those few frames are worth
        // re-decoding at full resolution for a genuinely sharp tile.
        val personClusters = cropRefiner.refine(videoUri, draftClusters) { cluster ->
            RepresentativeShotSelector.rank(cluster.appearances.flatMap { it.detections })
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
            videoDurationMs = if (sweep.durationMs > 0L) sweep.durationMs else sweep.lastTimestampMs,
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
}
