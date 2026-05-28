package com.koupper.monitor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// MCP server embedded in the monitor process.
// Tools are executed in-process via callTool(); the HTTP endpoint on port 18082
// exposes the same tools to external clients (e.g. IDE MCP integrations).
//
// Security invariant: run_agent and pipeline_run ONLY accept agents that already
// exist as files in agentsDir. The LLM cannot inject or execute arbitrary code —
// it must create_agent first, then run_agent in a separate step.
internal class CortexMcpServer(
    private val jobsDir: File,
    private val agentsDir: File,
    private val httpPort: Int = 18082
) {
    private val mapper = jacksonObjectMapper()

    private data class Tool(
        val descriptor: ToolDef,
        val handler: (Map<String, Any?>) -> Any
    )

    data class ToolDef(
        val name: String,
        val description: String,
        val parameters: Map<String, Any>
    )

    private val tools      = ConcurrentHashMap<String, Tool>()
    private val threadPool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
    private val running    = AtomicBoolean(false)
    private var serverSock: ServerSocket? = null

    init { registerAll() }

    // ── Registration helpers ──────────────────────────────────────────────────

    private fun reg(name: String, desc: String, params: Map<String, Any>, handler: (Map<String, Any?>) -> Any) {
        tools[name] = Tool(ToolDef(name, desc, params), handler)
    }

    private fun strP(desc: String) = mapOf("type" to "string", "description" to desc)
    private fun intP(desc: String) = mapOf("type" to "integer", "description" to desc)
    private fun arrP(desc: String) = mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to desc)
    private fun obj(vararg entries: Pair<String, Any>) = mapOf(*entries)
    private fun err(msg: String): Map<String, Any> = mapOf("error" to msg)
    private fun mcpTs() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    // ── Tool implementations ──────────────────────────────────────────────────

    private fun registerAll() {

        // 1 ─ list_agents ─────────────────────────────────────────────────────
        reg("list_agents",
            "List all agents available in the agent store (~/.koupper/agents/)",
            obj("type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>())
        ) { _ ->
            val agents = agentsDir.listFiles { f -> f.name.endsWith(".kts") }
                ?.map { f ->
                    val header = runCatching { f.readLines().take(6).joinToString("\n") }.getOrDefault("")
                    val desc   = Regex("//\\s*(?:Role|Objective|Description)\\s*:\\s*(.+)").find(header)
                        ?.groupValues?.get(1)?.trim() ?: ""
                    mapOf("name" to f.nameWithoutExtension, "description" to desc, "file" to f.name)
                } ?: emptyList()
            mapOf("agents" to agents, "count" to agents.size)
        }

        // 2 ─ run_agent ───────────────────────────────────────────────────────
        reg("run_agent",
            "Submit an agent from the store to a job queue. ONLY agents that already exist in ~/.koupper/agents/ can be run — no arbitrary code execution.",
            obj("type" to "object",
                "properties" to mapOf(
                    "name"  to strP("Agent name without .kts extension"),
                    "queue" to strP("Queue name (default: 'default')"),
                    "args"  to strP("Optional JSON string of arguments to pass to the agent")
                ),
                "required" to listOf("name"))
        ) { args ->
            val name    = args["name"]?.toString()  ?: return@reg err("name is required")
            val queue   = args["queue"]?.toString() ?: "default"
            val jobArgs = args["args"]?.toString()?.trim()?.takeIf { it.startsWith("{") } ?: "{}"

            // Security gate: agent file must exist in the store
            val agentFile = File(agentsDir, "$name.kts")
            if (!agentFile.exists()) {
                val available = agentsDir.listFiles { f -> f.name.endsWith(".kts") }
                    ?.map { it.nameWithoutExtension } ?: emptyList()
                return@reg err("Agent '$name' not found in agent store. Available: $available")
            }

            val jobId = "$name-${System.currentTimeMillis()}"
            val qDir  = File(jobsDir, queue).also { it.mkdirs() }
            File(qDir, "$jobId.json").writeText(
                """{"id":"$jobId","fileName":"$name","functionName":"run","scriptPath":"agents/$name.kts","sourceType":"script","args":$jobArgs,"submittedAt":"${mcpTs()}"}"""
            )
            mapOf("ok" to true, "jobId" to jobId, "queue" to queue, "agent" to name)
        }

        // 3 ─ job_status ──────────────────────────────────────────────────────
        reg("job_status",
            "Get the current status of a job by its ID",
            obj("type" to "object",
                "properties" to mapOf("jobId" to strP("Job ID to check")),
                "required" to listOf("jobId"))
        ) { args ->
            val id = args["jobId"]?.toString() ?: return@reg err("jobId is required")
            findJobStatus(id)
        }

        // 4 ─ read_log ────────────────────────────────────────────────────────
        reg("read_log",
            "Read the output log of a job (last N lines)",
            obj("type" to "object",
                "properties" to mapOf(
                    "jobId" to strP("Job ID to read logs for"),
                    "tail"  to intP("Number of lines from the end to return (default: 30)")
                ),
                "required" to listOf("jobId"))
        ) { args ->
            val id   = args["jobId"]?.toString()          ?: return@reg err("jobId is required")
            val tail = (args["tail"] as? Number)?.toInt() ?: 30
            readJobLog(id, tail)
        }

        // 5 ─ inspect_swarm ───────────────────────────────────────────────────
        reg("inspect_swarm",
            "Get a full snapshot of the swarm: all queues, job counts per state, and agent store size",
            obj("type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>())
        ) { _ -> inspectSwarm() }

        // 6 ─ create_agent ────────────────────────────────────────────────────
        reg("create_agent",
            "Save a generated agent script to the agent store. This is step 1. Use run_agent to execute it (step 2).",
            obj("type" to "object",
                "properties" to mapOf(
                    "name"    to strP("Agent name without .kts extension (alphanumeric, dash, underscore only)"),
                    "content" to strP("Full .kts script content")
                ),
                "required" to listOf("name", "content"))
        ) { args ->
            val name    = args["name"]?.toString()    ?: return@reg err("name is required")
            val content = args["content"]?.toString() ?: return@reg err("content is required")
            val safe    = name.replace(Regex("[^a-zA-Z0-9_-]"), "")
            if (safe.isEmpty()) return@reg err("invalid agent name — use alphanumeric, dash, or underscore only")
            agentsDir.mkdirs()
            File(agentsDir, "$safe.kts").writeText(content)
            mapOf("ok" to true, "saved" to "~/.koupper/agents/$safe.kts", "next" to "Use run_agent {\"name\":\"$safe\"} to execute it")
        }

        // 7 ─ pipeline_run ────────────────────────────────────────────────────
        reg("pipeline_run",
            "Run a sequential pipeline of agents. All stages must already exist in the agent store — no unknown agents allowed.",
            obj("type" to "object",
                "properties" to mapOf(
                    "stages" to arrP("Ordered list of agent names to run as pipeline stages"),
                    "queue"  to strP("Queue for the pipeline coordinator job (default: 'pipeline')")
                ),
                "required" to listOf("stages"))
        ) { args ->
            @Suppress("UNCHECKED_CAST")
            val stages = (args["stages"] as? List<*>)?.mapNotNull { it?.toString() }
                ?: return@reg err("stages must be a list of agent names")
            val queue = args["queue"]?.toString() ?: "pipeline"

            // Security gate: ALL stages must be in the store before anything is submitted
            val missing = stages.filter { !File(agentsDir, "$it.kts").exists() }
            if (missing.isNotEmpty())
                return@reg err("Agents not in store: $missing — create them with create_agent first")

            val pipelineId = "pipeline-${System.currentTimeMillis()}"
            File(agentsDir, "$pipelineId.kts").writeText(buildPipelineScript(pipelineId, stages))

            val qDir = File(jobsDir, queue).also { it.mkdirs() }
            File(qDir, "$pipelineId.json").writeText(
                """{"id":"$pipelineId","fileName":"$pipelineId","functionName":"run","scriptPath":"agents/$pipelineId.kts","sourceType":"script","submittedAt":"${mcpTs()}"}"""
            )
            mapOf("ok" to true, "pipelineId" to pipelineId, "stages" to stages, "queue" to queue)
        }

        // 8 ─ cancel_job ──────────────────────────────────────────────────────
        reg("cancel_job",
            "Cancel a pending or in-flight job by moving it to the failed state",
            obj("type" to "object",
                "properties" to mapOf("jobId" to strP("Job ID to cancel")),
                "required" to listOf("jobId"))
        ) { args ->
            val id = args["jobId"]?.toString() ?: return@reg err("jobId is required")
            cancelJob(id)
        }
    }

    // ── In-process tool execution (called by CortexEngine directly) ───────────

    fun callTool(name: String, arguments: Map<String, Any?>): Any {
        val tool = tools[name]
            ?: return err("Unknown tool '$name'. Available: ${tools.keys.sorted()}")
        return runCatching { tool.handler(arguments) }
            .getOrElse { e -> err("Tool '$name' error: ${e.message?.take(120)}") }
    }

    fun toolDefinitions(): List<Map<String, Any>> =
        tools.values.sortedBy { it.descriptor.name }.map { t ->
            mapOf("name" to t.descriptor.name, "description" to t.descriptor.description)
        }

    // ── HTTP server on port 18082 (for external MCP clients / IDE plugins) ────

    fun startHttp() {
        if (running.getAndSet(true)) return
        val socket = ServerSocket(httpPort, 50, InetAddress.getByName("127.0.0.1"))
        serverSock = socket
        threadPool.submit {
            while (running.get() && !socket.isClosed) {
                runCatching {
                    val client = socket.accept()
                    threadPool.submit { handleConnection(client) }
                }
            }
        }
    }

    fun stopHttp() {
        running.set(false)
        runCatching { serverSock?.close() }
        serverSock = null
    }

    private fun handleConnection(client: Socket) {
        client.use { sock ->
            runCatching {
                val input  = sock.getInputStream().bufferedReader()
                val output = sock.getOutputStream()

                val reqLine = input.readLine() ?: return
                val parts   = reqLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path   = parts[1].substringBefore("?")

                var contentLen = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:"))
                        contentLen = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                val body = if (contentLen > 0) {
                    val buf = CharArray(contentLen); input.read(buf, 0, contentLen); String(buf)
                } else ""

                val (status, responseBody) = routeHttp(method, path, body)
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                output.write(
                    "HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.write(bytes)
                output.flush()
            }
        }
    }

    private fun routeHttp(method: String, path: String, body: String): Pair<Int, String> = when {
        path == "/mcp/tools" && method == "GET"  -> handleListTools()
        path == "/mcp/call"  && method == "POST" -> handleCallTool(body)
        else -> 404 to """{"error":"not found: $path"}"""
    }

    private fun handleListTools(): Pair<Int, String> =
        200 to mapper.writeValueAsString(mapOf("tools" to tools.values.map { it.descriptor }))

    private fun handleCallTool(body: String): Pair<Int, String> {
        val payload = runCatching { mapper.readValue<Map<String, Any?>>(body) }
            .getOrElse { return 400 to """{"error":"invalid JSON body"}""" }
        val name = payload["name"]?.toString()
            ?: return 400 to """{"error":"field 'name' is required"}"""
        @Suppress("UNCHECKED_CAST")
        val arguments = payload["arguments"] as? Map<String, Any?> ?: emptyMap()
        val result = callTool(name, arguments)
        return 200 to mapper.writeValueAsString(mapOf("ok" to true, "name" to name, "result" to result))
    }

    // ── Swarm helpers ─────────────────────────────────────────────────────────

    private fun queueDirs() = jobsDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in setOf("logs", "commands") }
        ?: emptyList()

    private fun findJobStatus(jobId: String): Map<String, Any> {
        for (qDir in queueDirs()) {
            if (File(qDir, "$jobId.json.processing").exists())
                return mapOf("jobId" to jobId, "status" to "PROCESSING", "queue" to qDir.name)
            if (File(qDir, "$jobId.json").exists())
                return mapOf("jobId" to jobId, "status" to "PENDING", "queue" to qDir.name)
            if (File(File(qDir, ".failed"), "$jobId.json").exists())
                return mapOf("jobId" to jobId, "status" to "FAILED", "queue" to qDir.name)
        }
        return mapOf("jobId" to jobId, "status" to "NOT_FOUND")
    }

    private fun readJobLog(jobId: String, tail: Int): Map<String, Any> {
        val logFile = File(jobsDir, "logs").walkTopDown()
            .firstOrNull { it.name == "$jobId.log" }
        return if (logFile != null && logFile.exists()) {
            val lines = logFile.readLines().takeLast(tail)
            mapOf("jobId" to jobId, "lines" to lines, "count" to lines.size)
        } else {
            mapOf("jobId" to jobId, "lines" to emptyList<String>(), "error" to "log not found")
        }
    }

    private fun inspectSwarm(): Map<String, Any> {
        var pending = 0; var processing = 0; var failed = 0
        val queues  = mutableMapOf<String, Map<String, Int>>()

        for (qDir in queueDirs()) {
            var qP = 0; var qPr = 0
            for (f in qDir.listFiles() ?: emptyArray()) {
                when {
                    f.name.endsWith(".json.processing") -> { qPr++; processing++ }
                    f.name.endsWith(".json")            -> { qP++;  pending++ }
                }
            }
            val qF = File(qDir, ".failed").listFiles { f -> f.name.endsWith(".json") }?.size ?: 0
            failed += qF
            queues[qDir.name] = mapOf("pending" to qP, "processing" to qPr, "failed" to qF)
        }

        val agentCount = agentsDir.listFiles { f -> f.name.endsWith(".kts") }?.size ?: 0
        return mapOf(
            "queues"      to queues,
            "totals"      to mapOf("pending" to pending, "processing" to processing, "failed" to failed),
            "agentStore"  to mapOf("count" to agentCount, "path" to agentsDir.absolutePath)
        )
    }

    private fun cancelJob(jobId: String): Map<String, Any> {
        for (qDir in queueDirs()) {
            val pending    = File(qDir, "$jobId.json")
            val processing = File(qDir, "$jobId.json.processing")
            val failedDir  = File(qDir, ".failed").also { it.mkdirs() }
            when {
                pending.exists()    -> { pending.renameTo(File(failedDir, "$jobId.json"));    return mapOf("ok" to true, "jobId" to jobId, "was" to "PENDING") }
                processing.exists() -> { processing.renameTo(File(failedDir, "$jobId.json")); return mapOf("ok" to true, "jobId" to jobId, "was" to "PROCESSING") }
            }
        }
        return mapOf("ok" to false, "jobId" to jobId, "error" to "job not found in any queue")
    }

    // ── Pipeline script generator ─────────────────────────────────────────────
    // Emits structured markers that MonitorApp parses for the pipeline diagram:
    //   [PIPELINE:START:<stage1>,<stage2>,...]
    //   [PIPELINE:STAGE:<name>:START]
    //   [PIPELINE:STAGE:<name>:DONE:<elapsedMs>]   or :FAILED:<elapsedMs>
    //   [PIPELINE:DONE] | [PIPELINE:FAILED:<stageName>]

    private fun buildPipelineScript(pipelineId: String, stages: List<String>): String {
        val stageList  = stages.joinToString(",")
        val stageLines = stages.joinToString("\n\n") { name ->
            """println("[PIPELINE:STAGE:$name:START]")
val startMs_$name = System.currentTimeMillis()
val exit_$name = ProcessBuilder("${'$'}home/.koupper/bin/koupper", "run", "${'$'}home/.koupper/agents/$name.kts")
    .inheritIO().start().waitFor()
val elapsed_$name = System.currentTimeMillis() - startMs_$name
if (exit_$name != 0) {
    println("[PIPELINE:STAGE:$name:FAILED:${'$'}elapsed_$name]")
    println("[PIPELINE:FAILED:$name]")
    error("Stage $name failed (exit ${'$'}exit_$name)")
}
println("[PIPELINE:STAGE:$name:DONE:${'$'}elapsed_$name]")"""
        }
        return """
// $pipelineId.kts — Auto-generated pipeline coordinator
// Stages: ${stages.joinToString(" → ")}
// Created by CORTEX

import java.io.File

val home = System.getProperty("user.home")

println("[PIPELINE:START:$stageList]")

$stageLines

println("[PIPELINE:DONE]")
""".trimIndent()
    }
}
