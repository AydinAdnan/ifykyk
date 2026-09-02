package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Groups tracklets into people.
 *
 * Two changes matter relative to clustering raw detections. Identity decisions are made on
 * quality-weighted tracklet centroids rather than on single frames, so one blurred or
 * half-turned face can no longer split a person or merge two. And the "two faces visible
 * at once cannot be the same person" rule is enforced as a hard cannot-link constraint
 * that is inherited through merges: previously it was only checked between the two
 * clusters being merged, so A could merge with B and then the combined cluster could merge
 * with C even when C co-occurred with A.
 */
class AgglomerativeClusterer(
    private val embedder: FaceEmbedder,
    /**
     * Cosine similarity above which two groups of tracklets are the same person.
     *
     * Calibrated for L2-normalised FaceNet embeddings produced with correct prewhitening;
     * same-person pairs typically land above 0.6 and different-person pairs below 0.35.
     * The old 0.46 / 0.52 pair was compensating for a broken normalisation that pushed
     * every face toward the middle of the space.
     */
    private val similarityThreshold: Float = 0.55f,
    /** Minimum recognitionQuality for a detection to contribute to identity. */
    private val minRecognitionQuality: Float = 0.22f,
    /**
     * Extra similarity demanded before absorbing a low-confidence track into a person.
     *
     * A track made only of blurred or half-turned faces has a noisy centroid that can sit
     * closer to a stranger than to itself. Merging it on the same evidence as a clean
     * track makes people vanish: whoever only ever appears in the shaky part of the clip
     * gets quietly folded into someone who was well filmed.
     */
    private val lowConfidenceMargin: Float = 0.08f
) {

    /**
     * Clusters tracklets and returns, per person id (1-based, most prominent first), the
     * tracklets belonging to that person.
     */
    fun clusterTracklets(
        tracklets: List<Tracklet>,
        /**
         * Identity vector per tracklet id, normally supplied by TrackletEmbedder so the
         * model runs a few times per track rather than once per detection. Falls back to
         * averaging embeddings already stored on the detections.
         */
        precomputed: Map<Int, FloatArray>? = null
    ): Map<Int, List<Tracklet>> {
        if (tracklets.isEmpty()) return emptyMap()

        // Keyed by tracklet id, not by the tracklet itself: Tracklet is a data class
        // holding every detection, so hashing one walks the whole list on each lookup.
        val embeddings: Map<Int, FloatArray> = precomputed
            ?: tracklets.associate { it.id to trackletEmbedding(it, minRecognitionQuality) }

        // Tracklets with no usable face at all cannot be identified; they are still real
        // appearances, so they are attached to their best match later rather than dropped.
        val identifiable = tracklets.filter { embeddings.getValue(it.id).isNotEmpty() }
        val identifiableIds = identifiable.map { it.id }.toHashSet()
        val unidentifiable = tracklets.filter { it.id !in identifiableIds }

        if (identifiable.isEmpty()) {
            return tracklets.mapIndexed { i, t -> (i + 1) to listOf(t) }.toMap()
        }

        var groups = identifiable.map { mutableListOf(it) }.toMutableList()

        while (groups.size > 1) {
            var bestSim = -1f
            var mergeI = -1
            var mergeJ = -1

            for (i in groups.indices) {
                for (j in i + 1 until groups.size) {
                    if (coOccur(groups[i], groups[j])) continue

                    val sim = averageLinkage(groups[i], groups[j], embeddings)
                    if (sim > bestSim) {
                        bestSim = sim
                        mergeI = i
                        mergeJ = j
                    }
                }
            }

            if (mergeI < 0 || bestSim < requiredSimilarity(groups[mergeI], groups[mergeJ])) break

            groups[mergeI].addAll(groups[mergeJ])
            groups.removeAt(mergeJ)
        }

        // Tracklets with no usable face are attached only where the geometry genuinely
        // implies continuity: the same person, seen moments earlier or later, in nearly
        // the same place. Time proximity on its own was enough before, which meant a
        // stranger who happened to appear right after someone else was silently merged
        // into them and disappeared from the results.
        for (orphan in unidentifiable) {
            val host = groups.firstOrNull { group ->
                !coOccur(group, listOf(orphan)) &&
                    group.any { timeOverlapsClosely(it, orphan) && continuesSpatially(it, orphan) }
            }
            if (host != null) host.add(orphan) else groups.add(mutableListOf(orphan))
        }

        groups = groups.filter { it.isNotEmpty() }.toMutableList()
        groups.sortWith(
            compareByDescending<MutableList<Tracklet>> { group -> group.sumOf { it.detections.size } }
                .thenBy { group -> group.minOf { it.startMs } }
        )

        return groups.mapIndexed { index, group ->
            (index + 1) to group.sortedBy { it.startMs }
        }.toMap()
    }

    /**
     * Similarity two groups must reach to merge, raised when either side's identity rests
     * on poor-quality faces.
     */
    private fun requiredSimilarity(a: List<Tracklet>, b: List<Tracklet>): Float {
        val confidence = min(groupConfidence(a), groupConfidence(b))
        return similarityThreshold + (1f - confidence) * lowConfidenceMargin
    }

    /** How much the faces backing a group's identity can be trusted, in 0..1. */
    private fun groupConfidence(group: List<Tracklet>): Float {
        val best = group.flatMap { it.detections }
            .map { it.recognitionQuality }
            .sortedDescending()
            .take(3)
        return if (best.isEmpty()) 0f else best.average().toFloat().coerceIn(0f, 1f)
    }

    /**
     * True when any tracklet of one group shares a frame with any tracklet of the other.
     * Being visible simultaneously is proof of being different people, and because the
     * check runs over whole groups the constraint survives every merge.
     */
    private fun coOccur(a: List<Tracklet>, b: List<Tracklet>): Boolean {
        val framesA = HashSet<Int>()
        for (tracklet in a) framesA.addAll(tracklet.frameIndices)
        return b.any { tracklet -> tracklet.frameIndices.any { it in framesA } }
    }

    private fun averageLinkage(
        a: List<Tracklet>,
        b: List<Tracklet>,
        embeddings: Map<Int, FloatArray>
    ): Float {
        var sum = 0f
        var count = 0
        for (ta in a) {
            val ea = embeddings.getValue(ta.id)
            if (ea.isEmpty()) continue
            for (tb in b) {
                val eb = embeddings.getValue(tb.id)
                if (eb.isEmpty()) continue
                sum += embedder.cosineSimilarity(ea, eb)
                count++
            }
        }
        return if (count > 0) sum / count else -1f
    }

    private fun timeOverlapsClosely(a: Tracklet, b: Tracklet): Boolean {
        val gap = maxOf(a.startMs, b.startMs) - minOf(a.endMs, b.endMs)
        return gap <= 1000L
    }

    /**
     * True when the two tracklets meet in roughly the same part of the frame at roughly
     * the same scale, i.e. one plausibly continues the other.
     */
    private fun continuesSpatially(a: Tracklet, b: Tracklet): Boolean {
        val endOfA = (if (a.endMs <= b.startMs) a.detections.last() else a.detections.first())
            .boundingBox ?: return false
        val startOfB = (if (a.endMs <= b.startMs) b.detections.first() else b.detections.last())
            .boundingBox ?: return false

        val span = max(endOfA.width(), startOfB.width()).toFloat().coerceAtLeast(1f)
        val drift = hypot(
            endOfA.exactCenterX() - startOfB.exactCenterX(),
            endOfA.exactCenterY() - startOfB.exactCenterY()
        ) / span
        val scaleRatio = min(endOfA.width(), startOfB.width()).toFloat() /
            max(endOfA.width(), startOfB.width()).coerceAtLeast(1)

        return drift <= 1.5f && scaleRatio >= 0.5f
    }

    /**
     * Convenience entry point that tracks and clusters in one step, returning detections
     * grouped per person. Retained for tests and for callers that do not need tracklets.
     */
    fun clusterFaces(faces: List<DetectedFace>): Map<Int, List<DetectedFace>> {
        val tracklets = TrackletBuilder(embedder).build(faces)
        return clusterTracklets(tracklets).mapValues { (_, group) ->
            group.flatMap { it.detections }.sortedBy { it.timestampMs }
        }
    }
}
