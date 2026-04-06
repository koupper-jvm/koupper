package com.koupper.providers.vectordb

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

data class VectorRecord(
    val id: String,
    val vector: List<Double>,
    val metadata: Map<String, Any?> = emptyMap()
)

data class VectorMatch(
    val id: String,
    val score: Double,
    val metadata: Map<String, Any?>
)

interface VectorDbProvider {
    fun upsert(collection: String, vectors: List<VectorRecord>): Int
    fun query(collection: String, vector: List<Double>, topK: Int = 5, filter: Map<String, Any?> = emptyMap()): List<VectorMatch>
    fun delete(collection: String, ids: List<String>): Int
}

class LocalVectorDbProvider : VectorDbProvider {
    private val collections = ConcurrentHashMap<String, ConcurrentHashMap<String, VectorRecord>>()

    override fun upsert(collection: String, vectors: List<VectorRecord>): Int {
        val col = collections.computeIfAbsent(collection) { ConcurrentHashMap() }
        vectors.forEach { col[it.id] = it }
        return vectors.size
    }

    override fun query(collection: String, vector: List<Double>, topK: Int, filter: Map<String, Any?>): List<VectorMatch> {
        val col = collections[collection] ?: return emptyList()
        return col.values
            .asSequence()
            .filter { record -> filter.all { (k, v) -> record.metadata[k] == v } }
            .map { record ->
                VectorMatch(
                    id = record.id,
                    score = cosine(vector, record.vector),
                    metadata = record.metadata
                )
            }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    override fun delete(collection: String, ids: List<String>): Int {
        val col = collections[collection] ?: return 0
        var deleted = 0
        ids.forEach { id ->
            if (col.remove(id) != null) deleted++
        }
        return deleted
    }

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val minSize = minOf(a.size, b.size)
        var dot = 0.0
        var magA = 0.0
        var magB = 0.0
        for (i in 0 until minSize) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        if (magA == 0.0 || magB == 0.0) return 0.0
        return dot / (sqrt(magA) * sqrt(magB))
    }
}
