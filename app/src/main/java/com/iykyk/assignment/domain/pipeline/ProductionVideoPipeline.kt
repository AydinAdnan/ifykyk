package com.iykyk.assignment.domain.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.iykyk.assignment.domain.indexing.ConstrainedCommunityClusterer
import com.iykyk.assignment.domain.indexing.GlobalIdentityRegistry
import com.iykyk.assignment.domain.ml.FaceAlignmentHelper
import com.iykyk.assignment.domain.ml.FaceDetectorEngine
import com.iykyk.assignment.domain.ml.OnnxFaceEmbedder
import com.iykyk.assignment.domain.ml.SerFiqQualityEstimator
import com.iykyk.assignment.domain.model.*
import com.iykyk.assignment.domain.scene.SceneBoundaryDetector
import com.iykyk.assignment.domain.scene.TransitionDetector
import com.iykyk.assignment.domain.tracking.DeepSortLiteTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.max
import kotlin.math.min

/**
 * Production Video Face-Indexing Pipeline.
 *
 * Implements the full computer vision architecture:
 * 1. Scene Boundary Detection (cuts, dissolves, fades, zooms)
 * 2. Transition Detection (blur, whip pans, dissolves)
 * 3. Adaptive Frame Sampling (1 FPS static, 3 FPS normal, 5 FPS fast)
 * 4. Face Detection & Canonical 5-point Alignment (112x112 frontal + mirrored)
 * 5. DeepSORT-Lite Tracking (50% deep features, 30% IoU, 20% Kalman motion)
 * 6. SER-FIQ Quality Evaluation (recognition usefulness estimation)
 * 7. InsightFace Buffalo_SC ONNX Runtime Embeddings with XNNPACK
 * 8. HNSW Vector Indexing & Global Identity Registry
 * 9. Constrained Community Clustering with spatial cannot-link
 * 10. Temporal Appearance Event Counting with re-entry threshold
 * 11. 5-Tier Representative Shot Selection (Strict Solo Gate)
 * 12. 1280p High-Resolution Verification
 * 13. Hardware-accelerated Scrapbook Collage Rendering
 */
class ProductionVideoPipeline(private val context: Context) {

    private val frameExtractor = VideoFrameExtractor(context)
    private val onnxEmbedder = OnnxFaceEmbedder(context)
    private val faceDetector = FaceDetectorEngine(alignedCropSize = onnxEmbedder.inputSize)
    private val serFiqQualityEstimator = SerFiqQualityEstimator(onnxEmbedder)
    private val sceneDetector = SceneBoundaryDetector()
    private val transitionDetector = TransitionDetector()
    private val adaptiveSampler = AdaptiveFrameSampler()
    private val tracker = DeepSortLiteTracker(onnxEmbedder)
    private val identityRegistry = GlobalIdentityRegistry(onnxEmbedder)
    private val communityClusterer = ConstrainedCommunityClusterer(onnxEmbedder)
    private val appearanceSegmenter = AppearanceSegmenter(maxGapMs = 1500L, minSegmentDurationMs = 350L)
    private val cropRefiner = RepresentativeCropRefiner(frameExtractor, faceDetector)
    private val canvasRenderer = CollageCanvasRenderer(context)

    fun processVideo(videoUri: Uri): Flow<PipelineProgress> = channelFlow {
        val completedSteps = mutableSetOf<PipelineStep>()
        val benchmark = BenchmarkStats().apply { start() }

        sceneDetector.reset()
        transitionDetector.reset()
        adaptiveSampler.reset()
        tracker.reset()
        identityRegistry.reset()

        // 1. ADAPTIVE SAMPLING, SCENE DETECTION & FACE EXTRACTION
        send(
            PipelineProgress(
                currentStep = PipelineStep.EXTRACT_FRAMES,
                progressPercent = 10,
                statusMessage = "Analyzing scene boundaries & sampling video...",
                completedSteps = completedSteps
            )
        )

        val allObservedFaces = mutableListOf<DetectedFace>()
        var previewBitmap: Bitmap? = null
        var bestQualitySeen = 0f
        var framesProcessed = 0
        var lastEmittedPct = 10
        var previousQualityFaces = listOf<DetectedFace>()

        val sweepResult = frameExtractor.forEachFrame(videoUri) { frame, expectedTotal ->
            framesProcessed++
            benchmark.sampledFramesCount++

            // Scene boundary detection
            val sceneCut = sceneDetector.processFrame(frame.bitmap, frame.index, frame.timestampMs)
            if (sceneCut != null) {
                benchmark.sceneCutsCount++
                tracker.onSceneBoundary()
                previousQualityFaces = emptyList()
            }

            // Transition detection (whip pan, blur, dissolve)
            val transition = transitionDetector.analyzeFrame(frame.bitmap, frame.index, frame.timestampMs)
            if (transition != null) {
                benchmark.transitionsCount++
                tracker.onSceneBoundary()
                previousQualityFaces = emptyList()
            }

            // Visual delta analysis between consecutive frames
            val delta = adaptiveSampler.computeDelta(frame.bitmap)
            val isStaticScene = delta < AdaptiveFrameSampler.STATIC_DELTA_THRESHOLD && sceneCut == null && transition == null && framesProcessed > 1

            val qualityFaces: List<DetectedFace>
            if (isStaticScene && previousQualityFaces.isNotEmpty()) {
                // Delta optimization: Static scene - skip ML Kit detection & SerFiq computation.
                // Sustain tracks with propagated bounding boxes.
                qualityFaces = previousQualityFaces.map { prev ->
                    prev.copy(frameIndex = frame.index, timestampMs = frame.timestampMs)
                }
            } else {
                // Detect faces
                val tStartDet = System.currentTimeMillis()
                val detected = faceDetector.detectFacesInFrame(frame.bitmap, frame.index, frame.timestampMs)
                benchmark.detectionTimeMs += (System.currentTimeMillis() - tStartDet)
                benchmark.totalFacesDetected += detected.size

                val isMultiPerson = detected.size > 1
                qualityFaces = detected.map { face ->
                    val eval = serFiqQualityEstimator.evaluateFast(
                        sourceFrame = frame.bitmap,
                        box = face.boundingBox ?: android.graphics.Rect(0, 0, 100, 100),
                        landmarks = listOf(face.leftEye, face.rightEye, face.nose, face.mouthLeft, face.mouthRight),
                        eulerX = 0f,
                        eulerY = face.headEulerY,
                        eulerZ = face.headEulerZ
                    )
                    face.copy(
                        isSoloShot = !isMultiPerson && face.isSoloShot && face.otherFaceBoxesInFrame.isEmpty(),
                        embedding = FloatArray(0) // Defer ONNX embeddings to Step 2
                    )
                }
                previousQualityFaces = qualityFaces
            }

            // DeepSORT-Lite frame association
            val tStartTrack = System.currentTimeMillis()
            tracker.processFrame(qualityFaces)
            benchmark.trackingTimeMs += (System.currentTimeMillis() - tStartTrack)

            for (face in qualityFaces) {
                allObservedFaces.add(face)
                if (face.sharpnessScore > bestQualitySeen && face.generousCropBitmap != null) {
                    bestQualitySeen = face.sharpnessScore
                    previewBitmap = face.generousCropBitmap
                }
            }

            val pct = 10 + (framesProcessed * 35 / max(1, expectedTotal)).coerceAtMost(35)
            if (pct > lastEmittedPct || framesProcessed % 2 == 0) {
                lastEmittedPct = pct
                android.util.Log.i("ProductionPipeline", "Frame $framesProcessed / $expectedTotal ($pct%) - ${qualityFaces.size} faces")
                send(
                    PipelineProgress(
                        currentStep = PipelineStep.DETECT_FACES,
                        progressPercent = pct,
                        currentFaceBitmap = previewBitmap,
                        statusMessage = "Analyzing frame $framesProcessed of $expectedTotal (${allObservedFaces.size} faces)...",
                        completedSteps = completedSteps
                    )
                )
            }
        }

        completedSteps.add(PipelineStep.EXTRACT_FRAMES)
        completedSteps.add(PipelineStep.DETECT_FACES)

        val completedTracklets = tracker.finish()
        benchmark.totalTrackletsBuilt = completedTracklets.size

        if (completedTracklets.isEmpty() || allObservedFaces.isEmpty()) {
            send(
                PipelineProgress(
                    currentStep = PipelineStep.DETECT_FACES,
                    progressPercent = 0,
                    statusMessage = "No faces found in video",
                    completedSteps = completedSteps,
                    isFinished = true,
                    error = "No clear faces detected in this video."
                )
            )
            return@channelFlow
        }

        // 2. ONNX EMBEDDING GENERATION WITH MULTI-VECTOR PROFILE
        send(
            PipelineProgress(
                currentStep = PipelineStep.GENERATE_EMBEDDINGS,
                progressPercent = 50,
                currentFaceBitmap = previewBitmap,
                statusMessage = "Generating InsightFace Buffalo_SC embeddings...",
                completedSteps = completedSteps
            )
        )

        val trackletIdentities = mutableMapOf<Int, TrackletIdentity>()
        val tStartEmbed = System.currentTimeMillis()
        var embeddedCount = 0

        for (tracklet in completedTracklets) {
            val candidateFaces = tracklet.usableDetections(0.20f)
                .sortedByDescending { it.recognitionQuality }
                .take(2)
                .ifEmpty { tracklet.detections.take(1) }

            val vectors = mutableListOf<FloatArray>()
            val weights = mutableListOf<Float>()

            for (f in candidateFaces) {
                val vec = if (f.embedding.isNotEmpty()) f.embedding else onnxEmbedder.getEmbedding(f.alignedCropBitmap)
                if (vec.isNotEmpty()) {
                    vectors.add(vec)
                    weights.add(max(0.1f, f.recognitionQuality))
                    embeddedCount++
                }
            }

            if (vectors.isNotEmpty()) {
                val profile = onnxEmbedder.buildEmbeddingProfile(tracklet.id, vectors, weights)
                trackletIdentities[tracklet.id] = TrackletIdentity(
                    centroid = profile.qualityWeightedEmbedding,
                    members = vectors,
                    confidence = profile.meanQuality
                )
            } else {
                trackletIdentities[tracklet.id] = TrackletIdentity(FloatArray(0), emptyList(), 0f)
            }
        }
        benchmark.embeddingTimeMs = System.currentTimeMillis() - tStartEmbed
        benchmark.totalEmbeddingsComputed = embeddedCount
        completedSteps.add(PipelineStep.GENERATE_EMBEDDINGS)

        // 3. CONSTRAINED COMMUNITY CLUSTERING & GLOBAL REGISTRY
        send(
            PipelineProgress(
                currentStep = PipelineStep.CLUSTER_PEOPLE,
                progressPercent = 70,
                currentFaceBitmap = previewBitmap,
                statusMessage = "Clustering identities via HNSW community graph...",
                completedSteps = completedSteps
            )
        )

        val tStartClust = System.currentTimeMillis()
        val clusterMap = communityClusterer.cluster(completedTracklets, trackletIdentities)
        benchmark.clusteringTimeMs = System.currentTimeMillis() - tStartClust
        completedSteps.add(PipelineStep.CLUSTER_PEOPLE)

        // 4. APPEARANCE SEGMENTATION & STRICT SOLO REPRESENTATIVE SELECTION
        send(
            PipelineProgress(
                currentStep = PipelineStep.COUNT_APPEARANCES,
                progressPercent = 82,
                currentFaceBitmap = previewBitmap,
                statusMessage = "Counting appearance timelines & selecting solo portraits...",
                completedSteps = completedSteps
            )
        )

        val draftClusters = clusterMap.map { (personId, personTracklets) ->
            appearanceSegmenter.buildPerson(personId, personTracklets)
        }

        // 5. HIGH RESOLUTION 1280p REFINEMENT
        val tStartRefine = System.currentTimeMillis()
        val personClusters = cropRefiner.refine(videoUri, draftClusters) { cluster ->
            val allPersonDetections = clusterMap[cluster.id]?.flatMap { it.detections }
                ?: cluster.appearances.flatMap { it.detections }
            RepresentativeShotSelector.rank(allPersonDetections)
        }
        benchmark.refinementTimeMs = System.currentTimeMillis() - tStartRefine
        completedSteps.add(PipelineStep.COUNT_APPEARANCES)
        completedSteps.add(PipelineStep.SELECT_BEST_SHOTS)

        // 6. SCRAPBOOK COLLAGE GENERATION
        send(
            PipelineProgress(
                currentStep = PipelineStep.CREATE_COLLAGE,
                progressPercent = 95,
                currentFaceBitmap = personClusters.firstOrNull()?.representativeShot?.generousCropBitmap,
                statusMessage = "Rendering scrapbook stamp collage...",
                completedSteps = completedSteps
            )
        )

        val tStartCollage = System.currentTimeMillis()
        val collageBitmap = canvasRenderer.renderCollageBitmap(personClusters)
        benchmark.collageTimeMs = System.currentTimeMillis() - tStartCollage
        completedSteps.add(PipelineStep.CREATE_COLLAGE)

        benchmark.finish()
        val videoDuration = if (sweepResult.durationMs > 0) sweepResult.durationMs else sweepResult.lastTimestampMs
        val stats = benchmark.buildSummary(videoDuration)

        // 7. ASSEMBLE TARGET OUTPUT
        val targetOutput = buildTargetOutput(personClusters, collageBitmap, stats)

        val finalAnalysis = AnalysisResult(
            videoUri = videoUri.toString(),
            videoDurationMs = videoDuration,
            totalUniquePeople = personClusters.size,
            totalAppearances = personClusters.sumOf { it.appearanceCount },
            clusters = personClusters,
            collageBitmap = collageBitmap,
            targetOutput = targetOutput
        )

        send(
            PipelineProgress(
                currentStep = PipelineStep.CREATE_COLLAGE,
                progressPercent = 100,
                currentFaceBitmap = personClusters.firstOrNull()?.representativeShot?.generousCropBitmap,
                statusMessage = "Complete! Found ${personClusters.size} unique people in ${stats.totalProcessingTimeMs / 1000}s.",
                completedSteps = completedSteps,
                isFinished = true,
                finalResult = finalAnalysis
            )
        )
    }.flowOn(Dispatchers.Default)

    private fun buildTargetOutput(
        clusters: List<PersonCluster>,
        collageBitmap: Bitmap?,
        stats: ProcessingStatistics
    ): TargetOutput {
        val uniqueCount = clusters.size
        val personIds = clusters.map { it.id }
        val bestFaces = clusters.associate { it.id to (it.representativeShot.generousCropBitmap ?: Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)) }
        val appearanceCounts = clusters.associate { it.id to it.appearanceCount }

        val timeline = mutableListOf<AppearanceTimelineEntry>()
        val entryExits = mutableMapOf<Int, MutableList<TimeInterval>>()

        for (cluster in clusters) {
            val intervals = mutableListOf<TimeInterval>()
            for ((segIdx, segment) in cluster.appearances.withIndex()) {
                val entry = AppearanceTimelineEntry(
                    personId = cluster.id,
                    segmentIndex = segIdx,
                    entryTimestampMs = segment.startTimeMs,
                    exitTimestampMs = segment.endTimeMs,
                    durationMs = segment.durationMs,
                    peakQualityScore = segment.bestFace.sharpnessScore,
                    frameIndices = segment.detections.map { it.frameIndex }
                )
                timeline.add(entry)
                intervals.add(TimeInterval(segment.startTimeMs, segment.endTimeMs))
            }
            entryExits[cluster.id] = intervals
        }

        timeline.sortBy { it.entryTimestampMs }

        return TargetOutput(
            uniquePeopleCount = uniqueCount,
            personIdList = personIds,
            bestFaceImagePerPerson = bestFaces,
            appearanceCountPerPerson = appearanceCounts,
            appearanceTimeline = timeline,
            entryAndExitTimestamps = entryExits,
            finalCollageImage = collageBitmap,
            processingStatistics = stats
        )
    }
}
