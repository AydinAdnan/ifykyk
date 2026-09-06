package com.iykyk.assignment.domain.indexing

import java.util.PriorityQueue
import java.util.Random
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * High-Performance Hierarchical Navigable Small World (HNSW) Vector Index.
 * Enables O(log N) approximate nearest neighbor retrieval over high-dimensional face embeddings.
 * Eliminates exhaustive O(N^2) pairwise comparisons when indexing hundreds of tracklets.
 */
class HnswGraphIndex(
    private val dimension: Int = 128,
    private val m: Int = 16, // Max outgoing connections per node
    private val efConstruction: Int = 64, // Beam size during construction
    private val mL: Double = 1.0 / ln(16.0) // Normalization factor for level assignment
) {

    private data class Node(
        val id: Int,
        val vector: FloatArray,
        val level: Int,
        // neighbors per level: level -> list of neighbor node IDs
        val neighbors: Array<MutableList<Int>>
    )

    private val nodes = mutableMapOf<Int, Node>()
    private var entryPointId: Int? = null
    private var maxLevel = -1
    private val random = Random(42)

    val size: Int get() = nodes.size

    fun reset() {
        nodes.clear()
        entryPointId = null
        maxLevel = -1
    }

    /**
     * Inserts a vector into the HNSW graph index.
     */
    @Synchronized
    fun insert(id: Int, vector: FloatArray) {
        val level = assignLevel()
        val neighbors = Array(level + 1) { mutableListOf<Int>() }
        val newNode = Node(id, vector, level, neighbors)
        nodes[id] = newNode

        val currEp = entryPointId ?: run {
            entryPointId = id
            maxLevel = level
            return
        }

        var epId: Int = currEp
        // 1. Zoom through top layers down to level + 1 using greedy nearest step
        for (lc in maxLevel downTo level + 1) {
            epId = searchLayerGreedy(vector, epId, lc)
        }

        // 2. Connect in layers min(maxLevel, level) down to 0
        var candidates = mutableListOf(epId)
        for (lc in min(maxLevel, level) downTo 0) {
            val nearest = searchLayerBeam(vector, candidates, efConstruction, lc)
            val selectedNeighbors = selectNeighborsHeuristic(vector, nearest, m)

            for (nbrId in selectedNeighbors) {
                newNode.neighbors[lc].add(nbrId)
                val nbrNode = nodes[nbrId]
                if (nbrNode != null && lc <= nbrNode.level) {
                    nbrNode.neighbors[lc].add(id)
                    // Shrink neighbor list if it exceeds capacity
                    if (nbrNode.neighbors[lc].size > m) {
                        val pruned = selectNeighborsHeuristic(
                            nbrNode.vector,
                            nbrNode.neighbors[lc],
                            m
                        )
                        nbrNode.neighbors[lc].clear()
                        nbrNode.neighbors[lc].addAll(pruned)
                    }
                }
            }
            candidates = nearest
        }

        if (level > maxLevel) {
            maxLevel = level
            entryPointId = id
        }
    }

    /**
     * Queries the top-K nearest neighbors for the query vector using beam search.
     * Returns list of Pair(id, cosineSimilarity) sorted descending.
     */
    fun searchKnn(
        query: FloatArray,
        k: Int,
        efSearch: Int = 32
    ): List<Pair<Int, Float>> {
        val epId = entryPointId ?: return emptyList()
        var currEp = epId

        // 1. Greedy search through higher layers
        for (lc in maxLevel downTo 1) {
            currEp = searchLayerGreedy(query, currEp, lc)
        }

        // 2. Beam search on base layer 0
        val topCandidates = searchLayerBeam(query, listOf(currEp), max(k, efSearch), 0)

        // 3. Rank and return top K
        return topCandidates
            .map { id ->
                val vec = nodes.getValue(id).vector
                Pair(id, cosineSimilarity(query, vec))
            }
            .sortedByDescending { it.second }
            .take(k)
    }

    private fun searchLayerGreedy(query: FloatArray, entryId: Int, level: Int): Int {
        var curr = entryId
        var currSim = cosineSimilarity(query, nodes.getValue(curr).vector)
        var changed = true

        while (changed) {
            changed = false
            val nbrs = nodes.getValue(curr).neighbors.getOrNull(level) ?: break
            for (nbrId in nbrs) {
                val nbrNode = nodes[nbrId] ?: continue
                val sim = cosineSimilarity(query, nbrNode.vector)
                if (sim > currSim) {
                    currSim = sim
                    curr = nbrId
                    changed = true
                }
            }
        }
        return curr
    }

    private fun searchLayerBeam(
        query: FloatArray,
        entryPoints: List<Int>,
        ef: Int,
        level: Int
    ): MutableList<Int> {
        val visited = HashSet<Int>()
        // Min-heap of candidates to explore: orders by lowest similarity
        val candidates = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
        // Max-heap of nearest found results: orders by lowest similarity to poll worst
        val nearest = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })

        for (ep in entryPoints) {
            val sim = cosineSimilarity(query, nodes.getValue(ep).vector)
            candidates.add(Pair(ep, sim))
            nearest.add(Pair(ep, sim))
            visited.add(ep)
        }

        while (candidates.isNotEmpty()) {
            val (cId, cSim) = candidates.poll() ?: break
            val worstSim = nearest.peek()?.second ?: -1f

            if (cSim < worstSim && nearest.size >= ef) {
                break
            }

            val nbrs = nodes.getValue(cId).neighbors.getOrNull(level) ?: continue
            for (nbrId in nbrs) {
                if (!visited.add(nbrId)) continue
                val nbrNode = nodes[nbrId] ?: continue
                val sim = cosineSimilarity(query, nbrNode.vector)

                if (nearest.size < ef || sim > (nearest.peek()?.second ?: -1f)) {
                    candidates.add(Pair(nbrId, sim))
                    nearest.add(Pair(nbrId, sim))
                    if (nearest.size > ef) {
                        nearest.poll()
                    }
                }
            }
        }

        return nearest.map { it.first }.toMutableList()
    }

    private fun selectNeighborsHeuristic(
        target: FloatArray,
        candidates: List<Int>,
        maxNeighbors: Int
    ): List<Int> {
        if (candidates.size <= maxNeighbors) return candidates
        return candidates
            .distinct()
            .map { id -> Pair(id, cosineSimilarity(target, nodes.getValue(id).vector)) }
            .sortedByDescending { it.second }
            .take(maxNeighbors)
            .map { it.first }
    }

    private fun assignLevel(): Int {
        val r = random.nextDouble()
        return (-ln(r) * mL).toInt().coerceIn(0, 8)
    }

    private fun cosineSimilarity(u: FloatArray, v: FloatArray): Float {
        val len = min(u.size, v.size)
        var dot = 0f
        for (i in 0 until len) dot += u[i] * v[i]
        return dot.coerceIn(-1f, 1f)
    }
}
