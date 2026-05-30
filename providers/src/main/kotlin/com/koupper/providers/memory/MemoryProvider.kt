package com.koupper.providers.memory

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.providers.vectordb.HashEmbedder
import com.koupper.providers.vectordb.VectorDbProvider
import com.koupper.providers.vectordb.VectorRecord
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class MemoryEntry(
    val id: String,
    val text: String,
    val createdAt: String,
    val metadata: Map<String, Any?> = emptyMap()
)

data class MemoryMatch(
    val id: String,
    val text: String,
    val score: Double,
    val metadata: Map<String, Any?>
)

interface MemoryProvider {
    fun remember(text: String, metadata: Map<String, Any?> = emptyMap()): String
    fun recall(query: String, topK: Int = 5, minScore: Double = 0.1): List<MemoryMatch>
    fun forget(id: String): Boolean
    fun list(): List<MemoryEntry>
}

private const val COLLECTION = "memory"
private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

class LocalMemoryProvider(
    private val vectorDb: VectorDbProvider,
    private val storeDir: File
) : MemoryProvider {
    private val mapper = jacksonObjectMapper()
    private val texts = ConcurrentHashMap<String, MemoryEntry>()
    private val textsFile = File(storeDir, "memory-texts.json")
    private val memoryMd = File(storeDir, "memory.md")

    init {
        storeDir.mkdirs()
        loadTexts()
    }

    override fun remember(text: String, metadata: Map<String, Any?>): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val entry = MemoryEntry(id = id, text = text, createdAt = now(), metadata = metadata)
        texts[id] = entry
        vectorDb.upsert(COLLECTION, listOf(VectorRecord(id = id, vector = HashEmbedder.embed(text), metadata = metadata)))
        persistTexts()
        updateMemoryMd()
        return id
    }

    override fun recall(query: String, topK: Int, minScore: Double): List<MemoryMatch> {
        val queryVec = HashEmbedder.embed(query)
        return vectorDb.query(COLLECTION, queryVec, topK)
            .filter { it.score >= minScore }
            .mapNotNull { match ->
                val entry = texts[match.id] ?: return@mapNotNull null
                MemoryMatch(id = match.id, text = entry.text, score = match.score, metadata = entry.metadata)
            }
    }

    override fun forget(id: String): Boolean {
        val removed = texts.remove(id) != null
        if (removed) {
            vectorDb.delete(COLLECTION, listOf(id))
            persistTexts()
            updateMemoryMd()
        }
        return removed
    }

    override fun list(): List<MemoryEntry> = texts.values.sortedByDescending { it.createdAt }

    private fun persistTexts() {
        textsFile.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(texts.values.toList()))
    }

    private fun loadTexts() {
        if (!textsFile.exists() || textsFile.readText().isBlank()) return
        runCatching {
            val entries = mapper.readValue(textsFile.readText(), object : TypeReference<List<MemoryEntry>>() {})
            entries.forEach { texts[it.id] = it }
        }
    }

    private fun updateMemoryMd() {
        val sb = StringBuilder("# Koupper Memory\n\n")
        sb.append("_${texts.size} entries — last updated ${now()}_\n\n")
        texts.values.sortedByDescending { it.createdAt }.forEach { e ->
            sb.append("- **[${e.id}]** `${e.createdAt}` — ${e.text.take(120)}")
            if (e.text.length > 120) sb.append("…")
            if (e.metadata.isNotEmpty()) sb.append(" `${e.metadata}`")
            sb.append("\n")
        }
        memoryMd.writeText(sb.toString())
    }

    private fun now() = LocalDateTime.now().format(TS_FMT)
}
