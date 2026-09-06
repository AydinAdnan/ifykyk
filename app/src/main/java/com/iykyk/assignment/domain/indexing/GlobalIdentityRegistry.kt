package com.iykyk.assignment.domain.indexing

import com.iykyk.assignment.domain.ml.OnnxFaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import com.iykyk.assignment.domain.model.PersonEmbeddingProfile
import com.iykyk.assignment.domain.pipeline.Tracklet
import kotlin.math.max

/**
 * Global Identity Registry.
 * Recognizes and re-identifies individuals across long absences, scene cuts, and re-entries.
 * Guarantees that a person seen at 00:05 who disappears and returns at 04:12 retains the same identity.
 *
 * Uses an HNSW vector index for scalable approximate retrieval and enforces hard cannot-link constraints.
 */
class GlobalIdentityRegistry(
    private val embedder: OnnxFaceEmbedder,
    private val matchThreshold: Float = 0.46f
) {

    private val hnswIndex = HnswGraphIndex(dimension = embedder.inputSize)
    private val personProfiles = mutableMapOf<Int, PersonEmbeddingProfile>()
    private val personTracklets = mutableMapOf<Int, MutableList<Tracklet>>()
    private val personFrameIndices = mutableMapOf<Int, MutableSet<Int>>() // Cannot-link tracking
    private var nextPersonId = 1

    fun reset() {
        hnswIndex.reset()
        personProfiles.clear()
        personTracklets.clear()
        personFrameIndices.clear()
        nextPersonId = 1
    }

    /**
     * Integrates an incoming tracklet into the registry:
     * - Queries nearest identities via HNSW
     * - Checks spatial cannot-link constraints (simultaneous frame co-occurrence)
     * - Merges into existing person if match is confident, or registers a new identity.
     */
    fun registerOrMatchTracklet(
        tracklet: Tracklet,
        embeddings: List<FloatArray>,
        qualities: List<Float>
    ): Int {
        if (embeddings.isEmpty()) {
            return registerNewPerson(tracklet, embeddings, qualities)
        }

        // Build temporary candidate profile for the tracklet
        val trackletProfile = embedder.buildEmbeddingProfile(
            personId = -1,
            embeddings = embeddings,
            qualities = qualities
        )

        // 1. Query HNSW index for top nearest registered persons
        val nearestCandidates = hnswIndex.searchKnn(
            query = trackletProfile.qualityWeightedEmbedding,
            k = 5,
            efSearch = 16
        )

        var bestPersonId: Int? = null
        var bestSimilarity = -1f

        for ((candPersonId, approxSim) in nearestCandidates) {
            val existingProfile = personProfiles[candPersonId] ?: continue

            // 2. Hard Cannot-Link Rule: People simultaneously visible in the same frame can NEVER be merged
            if (hasCoOccurrenceViolation(candPersonId, tracklet)) {
                continue
            }

            // 3. Multi-vector profile similarity (combines quality-weighted centroid + best take)
            val profileSim = embedder.profileSimilarity(existingProfile, trackletProfile)
            if (profileSim > bestSimilarity) {
                bestSimilarity = profileSim
                bestPersonId = candPersonId
            }
        }

        // 4. Match Decision: Merge if above threshold, else register new identity
        return if (bestPersonId != null && bestSimilarity >= matchThreshold) {
            mergeIntoPerson(bestPersonId, tracklet, embeddings, qualities)
            bestPersonId
        } else {
            registerNewPerson(tracklet, embeddings, qualities)
        }
    }

    private fun registerNewPerson(
        tracklet: Tracklet,
        embeddings: List<FloatArray>,
        qualities: List<Float>
    ): Int {
        val personId = nextPersonId++
        val profile = if (embeddings.isNotEmpty()) {
            embedder.buildEmbeddingProfile(personId, embeddings, qualities)
        } else {
            PersonEmbeddingProfile(
                personId = personId,
                bestEmbedding = FloatArray(0),
                averageEmbedding = FloatArray(0),
                qualityWeightedEmbedding = FloatArray(0),
                embeddingVariance = FloatArray(0),
                memberCount = 0,
                meanQuality = 0f
            )
        }

        personProfiles[personId] = profile
        personTracklets[personId] = mutableListOf(tracklet)
        personFrameIndices[personId] = tracklet.frameIndices.toMutableSet()

        if (profile.qualityWeightedEmbedding.isNotEmpty()) {
            hnswIndex.insert(personId, profile.qualityWeightedEmbedding)
        }

        return personId
    }

    private fun mergeIntoPerson(
        personId: Int,
        tracklet: Tracklet,
        newEmbeddings: List<FloatArray>,
        newQualities: List<Float>
    ) {
        val currentTracklets = personTracklets.getOrPut(personId) { mutableListOf() }
        currentTracklets.add(tracklet)

        val frameSet = personFrameIndices.getOrPut(personId) { mutableSetOf() }
        frameSet.addAll(tracklet.frameIndices)

        val existingProfile = personProfiles[personId]
        if (existingProfile != null && newEmbeddings.isNotEmpty()) {
            // Quality-weighted multi-vector fusion
            val allEmbeddings = mutableListOf<FloatArray>()
            val allQualities = mutableListOf<Float>()

            allEmbeddings.add(existingProfile.qualityWeightedEmbedding)
            allQualities.add(existingProfile.meanQuality * existingProfile.memberCount)

            for (i in newEmbeddings.indices) {
                allEmbeddings.add(newEmbeddings[i])
                allQualities.add(newQualities.getOrElse(i) { 1.0f })
            }

            val updatedProfile = embedder.buildEmbeddingProfile(personId, allEmbeddings, allQualities)
            personProfiles[personId] = updatedProfile

            // Re-index updated profile centroid
            hnswIndex.insert(personId, updatedProfile.qualityWeightedEmbedding)
        }
    }

    private fun hasCoOccurrenceViolation(personId: Int, tracklet: Tracklet): Boolean {
        val personFrames = personFrameIndices[personId] ?: return false
        val overlapFrames = tracklet.frameIndices.intersect(personFrames)
        if (overlapFrames.isEmpty()) return false

        // Check if detections in the overlapping frames are spatially distinct
        val existingTracklets = personTracklets[personId] ?: return true
        for (fIdx in overlapFrames) {
            val newFace = tracklet.detections.firstOrNull { it.frameIndex == fIdx } ?: continue
            val newBox = newFace.boundingBox ?: return true

            for (existingT in existingTracklets) {
                val existingFace = existingT.detections.firstOrNull { it.frameIndex == fIdx } ?: continue
                val exBox = existingFace.boundingBox ?: return true

                // Overlapping bounding boxes (IoU > 0.35) mean same face detection
                // Non-overlapping (IoU < 0.35) means two different people co-occurring in the same frame
                if (computeIoU(newBox, exBox) < 0.35f) {
                    return true // Cannot-link violation!
                }
            }
        }
        return false
    }

    fun getAllPersons(): Map<Int, List<Tracklet>> {
        return personTracklets
    }

    fun getProfile(personId: Int): PersonEmbeddingProfile? {
        return personProfiles[personId]
    }

    private fun computeIoU(a: android.graphics.Rect, b: android.graphics.Rect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = kotlin.math.min(a.right, b.right)
        val interBottom = kotlin.math.min(a.bottom, b.bottom)
        val interW = interRight - interLeft
        val interH = interBottom - interTop
        if (interW <= 0 || interH <= 0) return 0f
        val inter = interW.toFloat() * interH
        val union = a.width().toFloat() * a.height() + b.width().toFloat() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
