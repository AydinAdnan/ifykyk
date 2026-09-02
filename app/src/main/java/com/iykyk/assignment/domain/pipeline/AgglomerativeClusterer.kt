package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace

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
    private val minRecognitionQuality: Float = 0.22f
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

            if (bestSim < similarityThreshold || mergeI < 0) break

            groups[mergeI].addAll(groups[mergeJ])
            groups.removeAt(mergeJ)
        }

        // Attach unidentifiable tracklets to whichever person they overlap in time and
        // space, otherwise leave them as their own person.
        for (orphan in unidentifiable) {
            val host = groups.firstOrNull { group ->
                !coOccur(group, listOf(orphan)) &&
                    group.any { timeOverlapsClosely(it, orphan) }
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
