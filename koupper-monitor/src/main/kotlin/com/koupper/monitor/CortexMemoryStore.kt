package com.koupper.monitor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.sqrt

// Persistent semantic memory for CORTEX sessions.
//
// On each user turn:
//   1. Embeds the query (llama-server /v1/embeddings, TF-IDF fallback)
//   2. Retrieves top-K relevant past turns via cosine similarity
//   3. Returns them as context text to inject before inference
//
// After each complete Q&A turn, stores the pair for future retrieval.
// Memory is persisted to ~/.koupper/memory/cortex_memory.json.
internal class CortexMemoryStore(
    private val http: HttpClient,
    private val llamaPort: Int = 8081,
    private val memoryFile: File
) {
    private val mapper = jacksonObjectMapper()

    data class MemoryEntry(
        val id: String,
        val userMsg: String,
        val assistantReply: String,
        val timestamp: String,
        val vector: List<Double>          // embedding or TF-IDF; empty = not embedded yet
    )

    private val entries = mutableListOf<MemoryEntry>()
    private var useEmbeddings = true      // false after first embedding failure

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun load() {
        if (!memoryFile.exists()) return
        runCatching {
            entries.clear()
            entries.addAll(mapper.readValue<List<MemoryEntry>>(memoryFile))
        }
    }

    fun save() {
        memoryFile.parentFile?.mkdirs()
        runCatching { memoryFile.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries)) }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    // Returns formatted context string for the top-K most relevant past turns.
    // Empty string if nothing relevant is found.
    fun retrieve(query: String, topK: Int = 3): String {
        if (entries.isEmpty()) return ""

        val queryVec = embed(query)
        val scored   = entries.map { e -> e to cosine(queryVec, e.vector) }
            .filter  { (_, score) -> score > 0.15 }    // only meaningful matches
            .sortedByDescending { (_, score) -> score }
            .take(topK)

        if (scored.isEmpty()) return ""

        return buildString {
            appendLine("CONTEXT FROM PREVIOUS SESSIONS (most relevant):")
            for ((entry, score) in scored) {
                appendLine("  [${entry.timestamp}] (relevance: ${"%.2f".format(score)})")
                appendLine("  User: ${entry.userMsg.take(120)}")
                appendLine("  CORTEX: ${entry.assistantReply.take(200)}")
            }
        }.trim()
    }

    // Stores a completed Q&A turn and persists to disk.
    fun store(userMsg: String, assistantReply: String) {
        val vec = embed("$userMsg $assistantReply")
        entries.add(MemoryEntry(
            id             = System.currentTimeMillis().toString(),
            userMsg        = userMsg.take(500),
            assistantReply = assistantReply.take(1000),
            timestamp      = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
            vector         = vec
        ))
        // Keep last 500 entries — older ones are less useful
        if (entries.size > 500) entries.removeAt(0)
        save()
    }

    // ── Embedding ─────────────────────────────────────────────────────────────

    // Tries llama-server /v1/embeddings. Falls back to TF-IDF on any error.
    private fun embed(text: String): List<Double> {
        if (useEmbeddings) {
            val vec = llamaEmbed(text)
            if (vec.isNotEmpty()) return vec
            useEmbeddings = false  // disable for rest of session after first failure
        }
        return tfidf(text, entries.map { "${it.userMsg} ${it.assistantReply}" })
    }

    private fun llamaEmbed(text: String): List<Double> = runCatching {
        val body = mapper.writeValueAsString(mapOf("input" to text, "model" to "local"))
        val req  = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$llamaPort/v1/embeddings"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return@runCatching emptyList()

        val root = mapper.readTree(resp.body())
        val arr  = root.get("data")?.get(0)?.get("embedding") ?: return@runCatching emptyList()
        arr.map { it.doubleValue() }
    }.getOrDefault(emptyList())

    // ── TF-IDF fallback ───────────────────────────────────────────────────────

    private fun tfidf(text: String, corpus: List<String>): List<Double> {
        val tokens   = tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val vocab    = (tokens + corpus.flatMap { tokenize(it) }).toSet().sorted()
        val tf       = tokens.groupingBy { it }.eachCount()
        val docCount = corpus.size.coerceAtLeast(1)
        return vocab.map { term ->
            val tfVal  = (tf[term] ?: 0).toDouble() / tokens.size
            val dfCount = corpus.count { term in tokenize(it) } + 1
            val idf    = ln((docCount + 1.0) / dfCount.toDouble())
            tfVal * idf
        }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.length > 2 }

    // ── Cosine similarity ──────────────────────────────────────────────────────

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val size = minOf(a.size, b.size)
        var dot = 0.0; var magA = 0.0; var magB = 0.0
        for (i in 0 until size) {
            dot  += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        return if (magA == 0.0 || magB == 0.0) 0.0 else dot / (sqrt(magA) * sqrt(magB))
    }
}
