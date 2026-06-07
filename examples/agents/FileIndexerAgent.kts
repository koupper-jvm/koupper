// FileIndexerAgent — indexes local files (PDF/TXT/MD/CSV/…) into LocalVectorDb
//
// Embedding strategy:
//   EMBEDDER_MODEL=nomic-embed-text (default) → Ollama semantic embeddings (768-dim)
//   EMBEDDER_MODEL=hash                       → HashEmbedder keyword embeddings (512-dim, no Ollama required)
//   Auto-fallback: if Ollama is down, HashEmbedder is used automatically.
//
// ⚠ Changing EMBEDDER_MODEL invalidates existing collections — the agent clears and
//   re-indexes automatically when it detects the embedder has changed.
//
// Env vars:
//   INDEXER_DIR              — directory to walk (required, or pass watchDir param)
//   INDEXER_COLLECTION       — vector DB collection name (default: cortex-knowledge)
//   INDEXER_EXTENSIONS       — comma-separated extensions (default: txt,md,pdf)
//   INDEXER_LOOP_INTERVAL_S  — if >0, run in continuous loop with this interval in seconds
//   EMBEDDER_URL             — Ollama base URL (default: http://localhost:11434)
//   EMBEDDER_MODEL           — embedding model (default: nomic-embed-text) or "hash"

import com.koupper.shared.annotations.Export
import com.koupper.container.app
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.TextFileHandler
import com.koupper.providers.files.fromJson
import com.koupper.providers.files.toJson
import com.koupper.providers.pdf.PDFReaderProvider
import com.koupper.providers.vectordb.VectorDbProvider
import com.koupper.providers.vectordb.VectorRecord
import com.koupper.providers.vectordb.HashEmbedder
import com.koupper.providers.vectordb.OllamaEmbedder
import java.io.File
import java.time.Instant

// ── providers ─────────────────────────────────────────────────────────────────

val fileHandler = app.getInstance(FileHandler::class)
val textHandler = app.getInstance(TextFileHandler::class)
val pdfReader   = app.getInstance(PDFReaderProvider::class)
val vectorDb    = app.getInstance(VectorDbProvider::class)

// ── config ────────────────────────────────────────────────────────────────────

val STATE_DIR     = File(System.getProperty("user.home"), ".koupper/indexer")
val CHUNK_WORDS   = 300
val CHUNK_OVERLAP = 50

val embedUrl   = System.getenv("EMBEDDER_URL")   ?: "http://localhost:11434"
val embedModel = System.getenv("EMBEDDER_MODEL") ?: "nomic-embed-text"

// Resolve embedder at startup — prefer Ollama unless "hash" is forced or Ollama is down
val useOllama: Boolean = embedModel != "hash" && OllamaEmbedder.isAvailable(embedUrl)
val resolvedEmbedder   = if (useOllama) "ollama:$embedModel" else "hash"

// ── utilities ─────────────────────────────────────────────────────────────────

fun log(msg: String) = println("[FileIndexerAgent] ${Instant.now()} $msg")

fun embed(text: String): List<Double> {
    if (!useOllama) return HashEmbedder.embed(text)
    val result = OllamaEmbedder.embed(text, embedUrl, embedModel)
    return if (result.isNotEmpty()) result else HashEmbedder.embed(text)
}

fun chunkText(text: String): List<String> {
    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < words.size) {
        val end = minOf(start + CHUNK_WORDS, words.size)
        chunks += words.subList(start, end).joinToString(" ")
        if (end == words.size) break
        start += CHUNK_WORDS - CHUNK_OVERLAP
    }
    return chunks
}

fun extractText(filePath: String): String {
    return when (File(filePath).extension.lowercase()) {
        "pdf"                         -> runCatching { pdfReader.extractText(filePath) }.getOrElse { "" }
        "txt", "md", "markdown",
        "log", "csv", "json", "yaml",
        "yml", "xml", "html", "htm"   -> runCatching { textHandler.read(filePath) }.getOrElse { "" }
        else                          -> ""
    }
}

fun loadState(stateFile: File): MutableMap<String, Any> {
    if (!stateFile.exists()) return mutableMapOf()
    return runCatching {
        stateFile.readText().fromJson<MutableMap<String, Any>>() ?: mutableMapOf()
    }.getOrElse { mutableMapOf() }
}

fun saveState(stateFile: File, state: Map<String, Any>) {
    STATE_DIR.mkdirs()
    stateFile.writeText(state.toJson())
}

// ── indexing logic ────────────────────────────────────────────────────────────

fun runIndexPass(watchDir: String, collection: String, extensions: List<String>): Map<String, Int> {
    val stateFile = File(STATE_DIR, "${collection}-state.json")
    val state     = loadState(stateFile)

    // Detect embedder change — clear collection to avoid dimension mismatch
    val prevEmbedder = state["_embedder"]?.toString()
    if (prevEmbedder != null && prevEmbedder != resolvedEmbedder) {
        log("Embedder changed ($prevEmbedder → $resolvedEmbedder) — clearing collection for full re-index")
        vectorDb.clear(collection)
        state.clear()
    }
    state["_embedder"] = resolvedEmbedder

    val allFiles = fileHandler.listFiles(watchDir, recursive = true, extensions = extensions)
    var indexed  = 0
    var skipped  = 0
    var failed   = 0

    log("Scanning $watchDir — ${allFiles.size} file(s) found (exts: $extensions, collection: $collection, embedder: $resolvedEmbedder)")

    for (filePath in allFiles) {
        val f       = File(filePath)
        val lastMod = f.lastModified()

        @Suppress("UNCHECKED_CAST")
        val storedMod = (state[filePath] as? Number)?.toLong()
        if (storedMod == lastMod) { skipped++; continue }

        val text = extractText(filePath)
        if (text.isBlank()) {
            log("  SKIP (no text) $filePath")
            failed++
            continue
        }

        val chunks  = chunkText(text)
        val records = chunks.mapIndexed { i, chunk ->
            VectorRecord(
                id     = "${filePath.hashCode()}_$i",
                vector = embed(chunk),
                metadata = mapOf(
                    "filePath"     to filePath,
                    "fileName"     to f.name,
                    "chunkIndex"   to i,
                    "chunkTotal"   to chunks.size,
                    "lastModified" to lastMod,
                    "snippet"      to chunk.take(400)
                )
            )
        }

        vectorDb.upsert(collection, records)
        state[filePath] = lastMod
        indexed++
        log("  INDEXED (${chunks.size} chunks) $filePath")
    }

    saveState(stateFile, state)
    log("Pass done — indexed: $indexed, skipped: $skipped, failed: $failed (embedder: $resolvedEmbedder)")
    return mapOf("indexed" to indexed, "skipped" to skipped, "failed" to failed)
}

// ── entry point ───────────────────────────────────────────────────────────────

@Export
val setup: (Map<String, String>) -> String = { params ->
    val watchDir     = params["watchDir"]             ?: System.getenv("INDEXER_DIR")             ?: error("INDEXER_DIR not set — provide watchDir param or INDEXER_DIR env var")
    val collection   = params["collection"]           ?: System.getenv("INDEXER_COLLECTION")      ?: "cortex-knowledge"
    val extStr       = params["extensions"]           ?: System.getenv("INDEXER_EXTENSIONS")      ?: "txt,md,pdf"
    val extensions   = extStr.split(",").map { it.trim().lowercase().trimStart('.') }.filter { it.isNotBlank() }
    val loopInterval = (params["loopIntervalSeconds"] ?: System.getenv("INDEXER_LOOP_INTERVAL_S") ?: "0").toLong()

    log("Embedder: $resolvedEmbedder (${if (useOllama) "Ollama at $embedUrl" else "HashEmbedder fallback"})")

    if (loopInterval > 0) {
        log("Loop mode — interval ${loopInterval}s, dir $watchDir")
        while (true) {
            runCatching { runIndexPass(watchDir, collection, extensions) }
                .onFailure { log("Pass error: ${it.message}") }
            Thread.sleep(loopInterval * 1000L)
        }
        "loop terminated"
    } else {
        val stats = runIndexPass(watchDir, collection, extensions)
        (stats + mapOf("embedder" to resolvedEmbedder)).toJson()
    }
}
