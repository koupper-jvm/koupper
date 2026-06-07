// CortexWebUiAgent.kts — IGLY CORTEX API backend
// Exposes REST + SSE endpoints consumed by koupper-dashboard (React front).
//
// Config:
//   CORTEX_JOBS_DIR  — jobs directory (default: ~/.koupper/jobs)
//   CORTEX_WEB_PORT  — HTTP port       (default: 18083)

import com.koupper.container.app
import com.koupper.providers.runtime.router.GrizzlyRuntimeRouterProvider
import com.koupper.providers.runtime.router.StreamResponse
import com.koupper.shared.annotations.Export
import com.koupper.providers.files.fromJson
import com.koupper.providers.files.toJson
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchKey
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

val jobsDir = File(System.getenv("CORTEX_JOBS_DIR") ?: "${System.getProperty("user.home")}/.koupper/jobs")
val uiPort  = System.getenv("CORTEX_WEB_PORT")?.toIntOrNull() ?: 18083

val excluded = setOf("logs", "commands")

// ── Job history ───────────────────────────────────────────────────────────────

val historyFile = File(jobsDir, ".history.jsonl")

data class HistoryEntry(
    val id: String,
    val queue: String,
    val status: String,
    val time: String,
    val finishedAt: String,
    val result: String? = null
)

val jobHistory = CopyOnWriteArrayList<HistoryEntry>()

fun loadHistory() {
    if (!historyFile.exists()) return
    historyFile.readLines()
        .takeLast(500)
        .forEach { line ->
            runCatching {
                val m = line.fromJson<Map<String, String>>()
                jobHistory.add(HistoryEntry(
                    id         = m["id"] ?: return@forEach,
                    queue      = m["queue"] ?: "",
                    status     = m["status"] ?: "DONE",
                    time       = m["time"] ?: "",
                    finishedAt = m["finishedAt"] ?: "",
                    result     = m["result"]
                ))
            }
        }
}

fun appendHistory(entry: HistoryEntry) {
    jobHistory.add(entry)
    if (jobHistory.size > 500) jobHistory.removeAt(0)
    runCatching {
        historyFile.appendText(
            mapOf(
                "id"         to entry.id,
                "queue"      to entry.queue,
                "status"     to entry.status,
                "time"       to entry.time,
                "finishedAt" to entry.finishedAt,
                "result"     to entry.result
            ).toJson() + "\n"
        )
        val lines = historyFile.readLines()
        if (lines.size > 500) historyFile.writeText(lines.takeLast(500).joinToString("\n") + "\n")
    }
}

fun ts() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
fun isoNow() = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

// ── SSE broadcast ─────────────────────────────────────────────────────────────

val sseClients = CopyOnWriteArrayList<(String) -> Unit>()

fun broadcast(data: String) {
    val dead = mutableListOf<(String) -> Unit>()
    sseClients.forEach { cb -> try { cb(data) } catch (_: Exception) { dead.add(cb) } }
    sseClients.removeAll(dead.toSet())
}

// ── Observability ─────────────────────────────────────────────────────────────

fun parseDurationMs(logFile: File): Long? = runCatching {
    logFile.readLines().lastOrNull { "[DONE]" in it || "[FAILED]" in it }
        ?.let { Regex("(\\d+)ms").find(it)?.groupValues?.get(1)?.toLongOrNull() }
}.getOrNull()

fun computeObservability(): Map<String, Any> {
    val now        = LocalDateTime.now()
    val isoFmt     = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    val oneHourAgo = now.minusHours(1)

    val recent = jobHistory.filter { entry ->
        runCatching {
            LocalDateTime.parse(entry.finishedAt, isoFmt).isAfter(oneHourAgo)
        }.getOrDefault(false)
    }

    val total   = recent.size
    val done    = recent.count { it.status == "DONE" }
    val failed  = recent.count { it.status == "FAILED" || it.status == "DEAD" }
    val successRate = if (total > 0) (done * 100.0 / total) else 100.0
    val jobsPerMin  = total / 60.0

    val durations = recent
        .filter { it.status == "DONE" }
        .mapNotNull { entry ->
            val logFile = File(jobsDir, "logs/${entry.queue}/${entry.id}.log")
            if (logFile.exists()) parseDurationMs(logFile) else null
        }
        .sorted()

    val p50 = if (durations.isNotEmpty()) durations[durations.size / 2] else 0L
    val p95 = if (durations.isNotEmpty())
        durations[(durations.size * 0.95).toInt().coerceAtMost(durations.size - 1)] else 0L

    val buckets = (0 until 12).map { i ->
        val bucketEnd   = now.minusMinutes(((11 - i) * 5).toLong())
        val bucketStart = bucketEnd.minusMinutes(5)
        val bucketDone  = recent.count { e ->
            runCatching {
                val t = LocalDateTime.parse(e.finishedAt, isoFmt)
                (t.isAfter(bucketStart) || t.isEqual(bucketStart)) && t.isBefore(bucketEnd)
            }.getOrDefault(false) && e.status == "DONE"
        }
        val bucketFailed = recent.count { e ->
            runCatching {
                val t = LocalDateTime.parse(e.finishedAt, isoFmt)
                (t.isAfter(bucketStart) || t.isEqual(bucketStart)) && t.isBefore(bucketEnd)
            }.getOrDefault(false) && (e.status == "FAILED" || e.status == "DEAD")
        }
        listOf(bucketDone, bucketFailed)
    }

    return mapOf(
        "jobsPerMin"     to String.format("%.2f", jobsPerMin),
        "successRate"    to String.format("%.1f", successRate),
        "p50Ms"          to p50,
        "p95Ms"          to p95,
        "totalLastHour"  to total,
        "doneLastHour"   to done,
        "failedLastHour" to failed,
        "sparkline"      to buckets
    )
}

// ── Swarm snapshot ────────────────────────────────────────────────────────────

fun swarmSnapshot(): Map<String, Any> {
    val userHome = System.getProperty("user.home")!!
    val jobs = mutableListOf<Map<String, Any?>>()
    var pending = 0; var processing = 0; var failed = 0

    jobsDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in excluded }
        ?.forEach { qDir ->
            qDir.listFiles()?.forEach { f ->
                when {
                    f.name.endsWith(".json.processing") -> {
                        processing++
                        jobs += mapOf("id" to f.name.removeSuffix(".json.processing"),
                            "queue" to qDir.name, "status" to "PROCESSING", "time" to ts())
                    }
                    f.name.endsWith(".json") -> {
                        pending++
                        jobs += mapOf("id" to f.nameWithoutExtension,
                            "queue" to qDir.name, "status" to "PENDING", "time" to ts())
                    }
                }
            }
            listOf(".done" to "DONE", ".failed" to "FAILED", ".dead" to "DEAD").forEach { (folder, status) ->
                File(qDir, folder).listFiles { f -> f.name.endsWith(".json") || f.name.endsWith(".result.json") }?.forEach { f ->
                    val id = f.name.removeSuffix(".json").removeSuffix(".result")
                    if (status == "FAILED") failed++
                    jobs += mapOf("id" to id, "queue" to qDir.name, "status" to status, "time" to "-")
                }
            }
        }

    val seenIds = jobs.map { it["id"] }.toMutableSet()
    val done = jobHistory.size
    jobHistory.asReversed().take(100).forEach { e ->
        if (e.id !in seenIds) {
            jobs += mapOf("id" to e.id, "queue" to e.queue, "status" to e.status, "time" to e.time, "result" to e.result)
            seenIds.add(e.id)
        }
    }

    val agentsDir  = File(userHome, ".koupper/agents")
    val agentFiles = agentsDir.listFiles()
        ?.filter { f -> f.name.endsWith(".kts") && !f.name.startsWith(".") }
        ?.sortedBy { it.nameWithoutExtension }
        ?: emptyList()

    val agents = agentFiles.map { f ->
        val agentName = f.nameWithoutExtension

        // Load skill.json if available
        val skillPath = agentsDir.absolutePath + "/" + agentName + ".skill.json"
        val skillFile = File(skillPath)
        val skillExists = skillFile.exists()
        @Suppress("UNCHECKED_CAST")
        val skill: Map<String, Any> = if (skillExists)
            runCatching { skillFile.readText().fromJson<Map<String, Any>>() }.getOrDefault(emptyMap())
        else emptyMap()

        // Fallback description from .kts header
        val headerLines = runCatching { f.readLines().take(8).joinToString("\n") }.getOrDefault("")
        val headerDesc = Regex("//\\s*(?:Role|Objective|Description)\\s*:\\s*(.+)")
            .find(headerLines)?.groupValues?.get(1)?.trim() ?: ""

        // Running status
        val procPath = jobsDir.absolutePath + "/$agentName/$agentName-session.json.processing"
        val isRunning = jobs.any { j ->
            (j["queue"] == agentName || j["id"] == "$agentName-session") && j["status"] == "PROCESSING"
        } || File(procPath).exists()

        // Basic metrics from job history
        val agentHistory = jobHistory.filter { it.queue == agentName }
        val totalRuns    = agentHistory.size
        val successRuns  = agentHistory.count { it.status == "DONE" }
        val failedRuns   = agentHistory.count { it.status == "FAILED" || it.status == "DEAD" }
        val successRate  = if (totalRuns > 0) successRuns * 100.0 / totalRuns else 100.0
        val lastRun      = agentHistory.lastOrNull()?.finishedAt ?: ""

        val desc = skill["description"]?.toString()?.takeIf { d -> d.isNotBlank() } ?: headerDesc

        mapOf(
            "name"        to agentName,
            "description" to desc,
            "role"        to (skill["role"]?.toString() ?: ""),
            "tags"        to (skill["tags"] ?: emptyList<String>()),
            "persistent"  to (skill["persistent"] ?: false),
            "providers"   to (skill["providers"] ?: emptyList<String>()),
            "triggers"    to (skill["triggers"] ?: emptyList<String>()),
            "envVars"     to (skill["envVars"] ?: emptyList<Map<String, Any>>()),
            "setup"       to (skill["setup"] ?: emptyList<String>()),
            "requires"    to (skill["requires"] ?: emptyList<String>()),
            "running"     to isRunning,
            "metrics"     to mapOf(
                "totalRuns"   to totalRuns,
                "successRuns" to successRuns,
                "failedRuns"  to failedRuns,
                "successRate" to String.format("%.1f", successRate),
                "lastRun"     to lastRun
            )
        )
    }

    val schedules = runCatching {
        val f = File(userHome, ".koupper/schedules.json")
        if (f.exists()) f.readText().fromJson<List<Map<String, Any>>>() else emptyList()
    }.getOrDefault(emptyList())

    val cortexActive = jobs.any { it["id"] == "cortex-session" && it["status"] == "PROCESSING" }

    return mapOf(
        "type"          to "snapshot",
        "jobs"          to jobs,
        "metrics"       to mapOf("pending" to pending, "processing" to processing, "done" to done, "failed" to failed),
        "observability" to computeObservability(),
        "agents"        to agents,
        "schedules"     to schedules,
        "cortexActive"  to cortexActive,
        "time"          to ts()
    )
}

// ── WatchService ──────────────────────────────────────────────────────────────

fun startWatcher() = Thread {
    val ws = FileSystems.getDefault().newWatchService()
    jobsDir.mkdirs()

    val keyToDir = mutableMapOf<WatchKey, File>()

    fun reg(d: File) {
        if (d.exists()) {
            val key = d.toPath().register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            keyToDir[key] = d
        }
    }

    reg(jobsDir)
    jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { reg(it) }

    while (true) {
        val key = ws.poll(500, TimeUnit.MILLISECONDS) ?: continue
        val dir = keyToDir[key]

        for (ev in key.pollEvents()) {
            @Suppress("UNCHECKED_CAST")
            val fname = (ev as? java.nio.file.WatchEvent<Path>)?.context()?.fileName?.toString() ?: continue

            if (ev.kind() == ENTRY_CREATE && dir == jobsDir) {
                val newDir = File(jobsDir, fname)
                if (newDir.isDirectory && !fname.startsWith(".") && fname !in excluded) reg(newDir)
            }

            if (ev.kind() == ENTRY_DELETE && dir != null && dir != jobsDir) {
                if (fname.endsWith(".json.processing")) {
                    val jobId      = fname.removeSuffix(".json.processing")
                    val failedFile = File(dir, ".failed/$jobId.json")
                    val deadFile   = File(dir, ".dead/$jobId.json")
                    val finalStatus = when {
                        deadFile.exists()   -> "DEAD"
                        failedFile.exists() -> "FAILED"
                        else                -> "DONE"
                    }
                    if (finalStatus != "FAILED") {
                        val result = runCatching {
                            val resultFile = File(dir, ".done/$jobId.result.json")
                            if (resultFile.exists()) {
                                val raw = resultFile.readText().fromJson<Map<String, Any?>>()
                                val r   = raw["result"]?.toString()?.take(500)
                                resultFile.delete()
                                r
                            } else null
                        }.getOrNull()
                        appendHistory(HistoryEntry(
                            id         = jobId,
                            queue      = dir.name,
                            status     = finalStatus,
                            time       = ts(),
                            finishedAt = isoNow(),
                            result     = result
                        ))
                    }
                }
            }
        }

        if (sseClients.isNotEmpty()) broadcast(swarmSnapshot().toJson())
        key.reset()
    }
}.also { it.isDaemon = true }.start()

// ── API routes ────────────────────────────────────────────────────────────────

@Export
val setup: () -> Unit = {
    loadHistory()
    startWatcher()

    val router = GrizzlyRuntimeRouterProvider()

    router.registerRouter {

        // Serve React dashboard index.html
        get<String> {
            path { "/" }
            script { {
                val indexFile = File(System.getProperty("user.home") + "/.koupper/web/index.html")
                if (indexFile.exists()) indexFile.readText()
                else "<!DOCTYPE html><html><body><h2>Dashboard not deployed.</h2><p>Run: npm run build in koupper-dashboard/</p></body></html>"
            } }
        }

        get<Unit> {
            path { "/api/swarm" }
            script { { swarmSnapshot().toJson() } }
        }

        get<String> {
            path { "/api/logs/{jobId}" }
            script { { jobId: String ->
                val logFile = File(jobsDir, "logs").walkTopDown()
                    .firstOrNull { it.name == "$jobId.log" }
                if (logFile != null && logFile.exists())
                    mapOf("jobId" to jobId, "lines" to logFile.readLines().takeLast(300)).toJson()
                else
                    mapOf("jobId" to jobId, "lines" to emptyList<String>(), "error" to "log not found").toJson()
            } }
        }

        get<String> {
            path { "/api/agent/{name}" }
            script { { name: String ->
                val agentsBase = System.getProperty("user.home") + "/.koupper/agents"
                val file = File("$agentsBase/$name.kts")
                if (file.exists())
                    mapOf("name" to name, "content" to file.readText()).toJson()
                else
                    mapOf("name" to name, "error" to "agent not found").toJson()
            } }
        }

        post<String> {
            path { "/api/run-agent" }
            script { { body: String ->
                runCatching {
                    val payload = body.fromJson<Map<String, String>>()
                    val name    = payload["name"]?.trim() ?: ""
                    val queue   = payload["queue"]?.trim()?.takeIf { it.isNotEmpty() } ?: "default"
                    if (name.isBlank()) {
                        mapOf("ok" to false, "error" to "name is required").toJson()
                    } else {
                        val agentFile = File(System.getProperty("user.home") + "/.koupper/agents/$name.kts")
                        if (!agentFile.exists()) {
                            mapOf("ok" to false, "error" to "agent not found: $name").toJson()
                        } else {
                            val jobId = "$name-${System.currentTimeMillis()}"
                            val qDir  = File(jobsDir, queue).also { it.mkdirs() }
                            File(qDir, "$jobId.json").writeText(
                                """{"id":"$jobId","fileName":"$name","functionName":"run","scriptPath":"agents/$name.kts","sourceType":"script","args":{},"submittedAt":"${java.time.LocalDateTime.now()}"}"""
                            )
                            mapOf("ok" to true, "jobId" to jobId, "queue" to queue).toJson()
                        }
                    }
                }.getOrElse { e -> mapOf("ok" to false, "error" to e.message).toJson() }
            } }
        }

        get<Unit> {
            path { "/api/history" }
            script { {
                mapOf("entries" to jobHistory.asReversed().take(200).map { e ->
                    mapOf("id" to e.id, "queue" to e.queue, "status" to e.status, "time" to e.time, "finishedAt" to e.finishedAt, "result" to e.result)
                }).toJson()
            } }
        }

        post<String> {
            path { "/api/cortex" }
            script { { body: String ->
                runCatching {
                    val payload = body.fromJson<Map<String, String>>()
                    val msg     = payload["message"]?.trim() ?: ""
                    if (msg.isNotBlank()) {
                        val cmdDir = File(jobsDir, "commands/wizard").also { it.mkdirs() }
                        File(cmdDir, "${System.currentTimeMillis()}.response").writeText(msg)
                        mapOf("ok" to true).toJson()
                    } else {
                        mapOf("ok" to false, "error" to "empty message").toJson()
                    }
                }.getOrElse { e -> mapOf("ok" to false, "error" to e.message).toJson() }
            } }
        }

        get<Unit> {
            path { "/events" }
            script { {
                object : StreamResponse {
                    override fun onData(callback: (String) -> Unit) {
                        sseClients.add(callback)
                        try { callback(swarmSnapshot().toJson()) } catch (_: Exception) {}
                    }
                    override fun onClose(callback: () -> Unit) {}
                }
            } }
        }

        post<String> {
            path { "/api/voice" }
            script { { body: String ->
                val text        = body.trim().ifBlank { "CORTEX en línea." }
                val edgeBin     = ProcessBuilder("which", "edge-tts").start()
                    .inputStream.bufferedReader().readLine()?.trim() ?: "edge-tts"

                // Detecta idioma por marcadores claros — el LLM ya responde en el idioma del usuario
                val hasSpanish  = text.any { it in "áéíóúñÁÉÍÓÚÑ¿¡" } ||
                                  text.lowercase().split(Regex("\\W+"))
                                      .count { it in setOf("de","la","el","que","es","un","una","con","para",
                                          "por","pero","como","más","ya","si","me","te","su","se","no","sí",
                                          "hola","está","hay","esto","eso","también","qué","cómo","muy") } >= 1
                val hasEnglish  = text.lowercase().split(Regex("\\W+"))
                                      .count { it in setOf("the","is","are","was","were","have","has","this",
                                          "that","with","from","they","their","there","about","will","would",
                                          "should","could","what","where","when","your","our","can","you") } >= 3
                val voiceEs     = System.getenv("K_VOICE_EDGE")    ?: "es-MX-DaliaNeural"
                val voiceEn     = System.getenv("K_VOICE_EDGE_EN") ?: "en-US-AriaNeural"
                val voice       = if (!hasSpanish && hasEnglish) voiceEn else voiceEs
                val webVoiceDir = File(System.getProperty("user.home"), ".koupper/web/voice").also { it.mkdirs() }
                val outFile     = File(webVoiceDir, "voice-${System.currentTimeMillis()}.mp3")

                val proc = ProcessBuilder(edgeBin, "--voice", voice, "--text", text, "--write-media", outFile.absolutePath)
                    .redirectErrorStream(true).start()
                proc.waitFor()

                // Keep only last 10 audio files
                webVoiceDir.listFiles { f -> f.name.endsWith(".mp3") || f.name.endsWith(".wav") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(10)
                    ?.forEach { it.delete() }

                if (outFile.exists() && outFile.length() > 0)
                    mapOf("url" to "/voice/${outFile.name}").toJson()
                else
                    mapOf("error" to "edge-tts failed").toJson()
            } }
        }

        get<String> {
            path { "/api/voice/status" }
            script { {
                val voice  = System.getenv("K_VOICE_EDGE") ?: "es-MX-DaliaNeural"
                val check  = ProcessBuilder("which", "edge-tts").start()
                val ready  = check.waitFor() == 0
                mapOf("ready" to ready, "engine" to "edge-tts", "voice" to voice).toJson()
            } }
        }
    }

    router.start(uiPort)

    // Serve React dashboard static files via Grizzly StaticHttpHandler
    val webRoot = System.getProperty("user.home") + "/.koupper/web"
    val httpServer = System.getProperties()["koupper.runtime.server"]
        as? org.glassfish.grizzly.http.server.HttpServer
    if (httpServer != null && File(webRoot).exists()) {
        // /assets/* → served from ~/.koupper/web/assets/ (Grizzly strips the path prefix)
        val assetsHandler = org.glassfish.grizzly.http.server.StaticHttpHandler("$webRoot/assets")
        assetsHandler.isFileCacheEnabled = false
        httpServer.serverConfiguration.addHttpHandler(assetsHandler, "/assets/")

        // /voice/* → generated WAV files
        val voiceWebDir = "$webRoot/voice"
        File(voiceWebDir).mkdirs()
        val voiceHandler = org.glassfish.grizzly.http.server.StaticHttpHandler(voiceWebDir)
        voiceHandler.isFileCacheEnabled = false
        httpServer.serverConfiguration.addHttpHandler(voiceHandler, "/voice/")

        // favicon and icons served from web root
        val rootStaticHandler = org.glassfish.grizzly.http.server.StaticHttpHandler(webRoot)
        rootStaticHandler.isFileCacheEnabled = false
        httpServer.serverConfiguration.addHttpHandler(rootStaticHandler, "/favicon.svg", "/icons.svg")
        println("  Serving dashboard from $webRoot")
    }

    println("◈ CORTEX API → http://localhost:$uiPort")
    println("  Press Ctrl+C to stop.")
    Thread.currentThread().join()
}
