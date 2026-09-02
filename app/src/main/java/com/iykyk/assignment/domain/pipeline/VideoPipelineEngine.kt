package com.iykyk.assignment.domain.pipeline

import android.content.Context
import android.net.Uri
import com.iykyk.assignment.domain.ml.FaceDetectorEngine
import com.iykyk.assignment.domain.ml.TFLiteFaceEmbedder
import com.iykyk.assignment.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class VideoPipelineEngine(private val context: Context) {

    private val frameExtractor = VideoFrameExtractor(context)
    private val faceDetector = FaceDetectorEngine()
    private val faceEmbedder = TFLiteFaceEmbedder(context)
    private val clusterer = AgglomerativeClusterer(faceEmbedder, similarityThreshold = 0.58f, centroidMergeThreshold = 0.64f)
    private val segmenter = AppearanceSegmenter(maxGapMs = 1200L, minSegmentDurationMs = 350L)
    private val canvasRenderer = CollageCanvasRenderer(context)

    fun processVideo(videoUri: Uri): Flow<PipelineProgress> = flow {
        val completedSteps = mutableSetOf<PipelineStep>()

        // 1. EXTRACT FRAMES
        emit(
            PipelineProgress(
                currentStep = PipelineStep.EXTRACT_FRAMES,
                progressPercent = 10,
                statusMessage = "Sampling video frames...",
                completedSteps = completedSteps
            )
        )

        val frames = frameExtractor.extractFrames(videoUri, targetFps = 4.0f) { current, total ->
            // frame extraction progress
        }
        completedSteps.add(PipelineStep.EXTRACT_FRAMES)

        if (frames.isEmpty()) {
            emit(
                PipelineProgress(
                    currentStep = PipelineStep.EXTRACT_FRAMES,
                    progressPercent = 0,
                    statusMessage = "No frames extracted from video",
                    completedSteps = completedSteps,
                    isFinished = true,
                    error = "Could not decode video frames."
                )
            )
            return@flow
        }

        // 2. DETECT FACES
        emit(
            PipelineProgress(
                currentStep = PipelineStep.DETECT_FACES,
                progressPercent = 25,
                statusMessage = "Detecting faces in ${frames.size} frames...",
                completedSteps = completedSteps
            )
        )

        val allDetectedFaces = mutableListOf<DetectedFace>()
        var bestFaceBitmap: android.graphics.Bitmap? = null
        var bestSharpness = 0f
        var lastEmittedPct = 25

        frames.forEachIndexed { idx, frame ->
            val faces = faceDetector.detectFacesInFrame(frame.bitmap, frame.index, frame.timestampMs)
            for (face in faces) {
                allDetectedFaces.add(face)
                if (face.sharpnessScore > bestSharpness && face.generousCropBitmap != null) {
                    bestSharpness = face.sharpnessScore
                    bestFaceBitmap = face.generousCropBitmap
                }
            }

            val pct = 25 + ((idx + 1) * 20 / frames.size)
            if (pct >= lastEmittedPct + 4 || idx == frames.lastIndex) {
                lastEmittedPct = pct
                emit(
                    PipelineProgress(
                        currentStep = PipelineStep.DETECT_FACES,
                        progressPercent = pct,
                        currentFaceBitmap = bestFaceBitmap,
                        statusMessage = "Found ${allDetectedFaces.size} face detections...",
                        completedSteps = completedSteps
                    )
                )
            }
        }
        completedSteps.add(PipelineStep.DETECT_FACES)

        if (allDetectedFaces.isEmpty()) {
            emit(
                PipelineProgress(
                    currentStep = PipelineStep.DETECT_FACES,
                    progressPercent = 0,
                    statusMessage = "No faces found in video",
                    completedSteps = completedSteps,
                    isFinished = true,
                    error = "No faces detected in this video."
                )
            )
            return@flow
        }

        // 3. GENERATE EMBEDDINGS
        emit(
            PipelineProgress(
                currentStep = PipelineStep.GENERATE_EMBEDDINGS,
                progressPercent = 50,
                currentFaceBitmap = bestFaceBitmap,
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
        emit(
            PipelineProgress(
                currentStep = PipelineStep.CLUSTER_PEOPLE,
                progressPercent = 68,
                currentFaceBitmap = bestFaceBitmap,
                statusMessage = "Clustering unique individuals...",
                completedSteps = completedSteps
            )
        )

        val clusterMap = clusterer.clusterFaces(facesWithEmbeddings)
        completedSteps.add(PipelineStep.CLUSTER_PEOPLE)

        // 5. COUNT APPEARANCES & SELECT BEST SHOTS
        emit(
            PipelineProgress(
                currentStep = PipelineStep.COUNT_APPEARANCES,
                progressPercent = 82,
                currentFaceBitmap = bestFaceBitmap,
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
        emit(
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
            videoDurationMs = frames.lastOrNull()?.timestampMs ?: 10000L,
            totalUniquePeople = personClusters.size,
            totalAppearances = totalAppearances,
            clusters = personClusters,
            collageBitmap = collageBitmap
        )

        // FINISHED
        emit(
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
