package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Groups tracklets into people.
 *
 * Identity is decided on quality-weighted tracklet centroids rather than single frames, so
 * one blurred or half-turned face cannot split a person or merge two. Faces visible in the
 * same frame are a hard cannot-link constraint, enforced across whole groups so it survives
 * every merge.
 *
 * The threshold at which two groups become one person is calibrated per video rather than
 * hardcoded - see [ThresholdCalibrator]. A constant cannot serve here: it has to separate
 * embeddings from an int8-quantised export whose noise level no published figure describes,
 * and the same constant that keeps four people apart in one clip splits one person four
 * ways in the next.
 */
class AgglomerativeClusterer(
    private val embedder: FaceEmbedder,
    /**
     * Threshold used when the footage yields too little evidence to calibrate, typically
     * because two people are never on screen at the same time.
     */
    private val similarityThreshold: Float = ThresholdCalibrator.DEFAULT_THRESHOLD,
    /** Minimum recognitionQuality for a detection to contribute to identity. */
    private val minRecognitionQuality: Float = 0.22f,
    /**
     * Extra similarity demanded before absorbing a low-confidence track into a person.
     *
     * Kept small. A track of only blurred faces does have a noisier centroid, but the
     * calibrated threshold already reflects the noise level of this footage, so a large
     * penalty here just reintroduces splitting.
     */
    private val lowConfidenceMargin: Float = 0.03f
) {

    /**
     * Clusters tracklets and returns, per person id (1-based, most prominent first), the
     * tracklets belonging to that person.
     */
    fun clusterTracklets(
        tracklets: List<Tracklet>,
        /**
         * Identity per tracklet id, normally supplied by TrackletEmbedder so the model
         * runs a few times per track rather than once per detection. Falls back to
         * averaging embeddings already stored on the detections.
         */
        precomputed: Map<Int, TrackletIdentity>? = null
    ): Map<Int, List<Tracklet>> {
        if (tracklets.isEmpty()) return emptyMap()

        // Keyed by tracklet id, not by the tracklet itself: Tracklet is a data class
        // holding every detection, so hashing one walks the whole list on each lookup.
        val identities: Map<Int, TrackletIdentity> = precomputed
            ?: tracklets.associate { it.id to identityFromDetections(it) }

        val identifiable = tracklets.filter { identities.getValue(it.id).isUsable }
        val identifiableIds = identifiable.map { it.id }.toHashSet()
        val unidentifiable = tracklets.filter { it.id !in identifiableIds }

        if (identifiable.isEmpty()) {
            return tracklets.mapIndexed { i, t -> (i + 1) to listOf(t) }.toMap()
        }

        val threshold = calibrateThreshold(identifiable, identities)
        var groups = identifiable.map { mutableListOf(it) }.toMutableList()

        while (groups.size > 1) {
            var bestSim = -1f
            var mergeI = -1
            var mergeJ = -1

            for (i in groups.indices) {
                for (j in i + 1 until groups.size) {
                    if (coOccur(groups[i], groups[j])) continue

                    val sim = averageLinkage(groups[i], groups[j], identities)
                    if (sim > bestSim) {
                        bestSim = sim
                        mergeI = i
                        mergeJ = j
                    }
                }
            }

            if (mergeI < 0) break
            val confidence = groupConfidence(groups[mergeI], groups[mergeJ], identities)
            if (bestSim < threshold + (1f - confidence) * lowConfidenceMargin) break

            groups[mergeI].addAll(groups[mergeJ])
            groups.removeAt(mergeJ)
        }

        // Tracklets with no usable face are attached only where the geometry genuinely
        // implies continuity: the same person, seen moments earlier or later, in nearly
        // the same place. Time proximity on its own was enough before, which meant a
        // stranger who happened to appear right after someone else was merged into them.
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
     * Places the merge threshold using the labels the video gives away for free: tracklets
     * sharing a frame are different people, and faces inside one tracklet are the same
     * person.
     */
    private fun calibrateThreshold(
        tracklets: List<Tracklet>,
        identities: Map<Int, TrackletIdentity>
    ): Float {
        val impostor = mutableListOf<Float>()
        for (i in tracklets.indices) {
            val a = identities.getValue(tracklets[i].id)
            for (j in i + 1 until tracklets.size) {
                if (!coOccur(listOf(tracklets[i]), listOf(tracklets[j]))) continue
                val b = identities.getValue(tracklets[j].id)
                impostor.add(embedder.cosineSimilarity(a.centroid, b.centroid))
            }
        }

        val genuine = mutableListOf<Float>()
        for (tracklet in tracklets) {
            val members = identities.getValue(tracklet.id).members
            for (i in members.indices) {
                for (j in i + 1 until members.size) {
                    genuine.add(embedder.cosineSimilarity(members[i], members[j]))
                }
            }
        }

        return ThresholdCalibrator.calibrate(genuine, impostor, similarityThreshold)
    }

    /** Builds an identity from embeddings already stored on the detections. */
    private fun identityFromDetections(tracklet: Tracklet): TrackletIdentity {
        val usable = tracklet.usableDetections(minRecognitionQuality).ifEmpty {
            tracklet.detections.filter { it.embedding.isNotEmpty() }
        }
        if (usable.isEmpty()) return TrackletIdentity(FloatArray(0), emptyList(), 0f)

        return TrackletIdentity(
            centroid = trackletEmbedding(tracklet, minRecognitionQuality),
            members = usable.map { it.embedding },
            confidence = usable.map { it.recognitionQuality }.average().toFloat().coerceIn(0f, 1f)
        )
    }

    /**
     * Confidence of the weaker side, since a merge is only as trustworthy as the poorer of
     * the two identities. Taking the stronger side would let a well-filmed person vouch for
     * a blurred track being folded into them, which is the merge the margin exists to
     * question.
     */
    private fun groupConfidence(
        a: List<Tracklet>,
        b: List<Tracklet>,
        identities: Map<Int, TrackletIdentity>
    ): Float =
        (a + b).map { identities.getValue(it.id).confidence }.minOrNull()?.coerceIn(0f, 1f) ?: 0f

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
        identities: Map<Int, TrackletIdentity>
    ): Float {
        var sum = 0f
        var count = 0
        for (ta in a) {
            val ea = identities.getValue(ta.id).centroid
            if (ea.isEmpty()) continue
            for (tb in b) {
                val eb = identities.getValue(tb.id).centroid
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
