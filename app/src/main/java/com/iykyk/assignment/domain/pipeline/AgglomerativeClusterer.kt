package com.iykyk.assignment.domain.pipeline

import com.iykyk.assignment.domain.ml.FaceEmbedder
import com.iykyk.assignment.domain.model.DetectedFace
import kotlin.math.sqrt

class AgglomerativeClusterer(
    private val embedder: FaceEmbedder,
    private val similarityThreshold: Float = 0.52f,
    private val centroidMergeThreshold: Float = 0.62f
) {

    /**
     * Clusters detected faces into unique individuals using a two-pass agglomerative strategy.
     */
    fun clusterFaces(faces: List<DetectedFace>): Map<Int, List<DetectedFace>> {
        if (faces.isEmpty()) return emptyMap()
        if (faces.size == 1) return mapOf(1 to faces)

        // Pass 1: Initial Agglomerative clustering with average linkage
        val clusters = mutableListOf<MutableList<DetectedFace>>()
        for (face in faces) {
            clusters.add(mutableListOf(face))
        }

        while (clusters.size > 1) {
            var bestSim = -1f
            var mergeI = -1
            var mergeJ = -1

            for (i in 0 until clusters.size) {
                for (j in i + 1 until clusters.size) {
                    val sim = averageLinkageSimilarity(clusters[i], clusters[j])
                    if (sim > bestSim) {
                        bestSim = sim
                        mergeI = i
                        mergeJ = j
                    }
                }
            }

            if (bestSim >= similarityThreshold && mergeI != -1 && mergeJ != -1) {
                val clusterJ = clusters.removeAt(mergeJ)
                clusters[mergeI].addAll(clusterJ)
            } else {
                break
            }
        }

        // Pass 2: Centroid merge pass to heal split clusters across lighting changes
        var merged = true
        while (merged && clusters.size > 1) {
            merged = false
            var bestCentroidSim = -1f
            var mergeI = -1
            var mergeJ = -1

            for (i in 0 until clusters.size) {
                val centroidI = computeCentroid(clusters[i])
                for (j in i + 1 until clusters.size) {
                    val centroidJ = computeCentroid(clusters[j])
                    val sim = embedder.cosineSimilarity(centroidI, centroidJ)
                    if (sim > bestCentroidSim) {
                        bestCentroidSim = sim
                        mergeI = i
                        mergeJ = j
                    }
                }
            }

            if (bestCentroidSim >= centroidMergeThreshold && mergeI != -1 && mergeJ != -1) {
                val clusterJ = clusters.removeAt(mergeJ)
                clusters[mergeI].addAll(clusterJ)
                merged = true
            }
        }

        // Sort clusters by number of faces descending (most seen first)
        val sortedClusters = clusters.sortedByDescending { it.size }
        val resultMap = mutableMapOf<Int, List<DetectedFace>>()
        sortedClusters.forEachIndexed { index, faceList ->
            resultMap[index + 1] = faceList
        }

        return resultMap
    }

    private fun averageLinkageSimilarity(c1: List<DetectedFace>, c2: List<DetectedFace>): Float {
        var sum = 0f
        var count = 0
        for (f1 in c1) {
            for (f2 in c2) {
                sum += embedder.cosineSimilarity(f1.embedding, f2.embedding)
                count++
            }
        }
        return if (count > 0) sum / count else 0f
    }

    private fun computeCentroid(cluster: List<DetectedFace>): FloatArray {
        if (cluster.isEmpty()) return FloatArray(0)
        val dim = cluster.first().embedding.size
        if (dim == 0) return FloatArray(0)

        val centroid = FloatArray(dim)
        for (face in cluster) {
            val emb = face.embedding
            for (i in 0 until minOf(dim, emb.size)) {
                centroid[i] += emb[i]
            }
        }
        val n = cluster.size.toFloat()
        for (i in 0 until dim) {
            centroid[i] /= n
        }

        // L2 normalize centroid
        var sumSq = 0.0
        for (x in centroid) sumSq += (x * x).toDouble()
        val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-8f)
        for (i in 0 until dim) centroid[i] /= norm

        return centroid
    }
}
