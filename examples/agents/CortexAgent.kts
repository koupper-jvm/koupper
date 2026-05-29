// CortexAgent.kts — CORTEX Orchestrator
// Uses Koupper's InferenceEngine SP (LlamaServerSidecar, SSE streaming).
// Communicates via CommandBridge files and MCP server on port 18082.
//
// Required env vars:
//   KOUPPER_LLM_MODEL_PATH   — path to .gguf model file
//   KOUPPER_LLM_EXECUTABLE   — path to llama-server binary (default: llama-server)
//   CORTEX_JOBS_DIR          — jobs dir (default: ~/.koupper/jobs)

import com.koupper.container.app
import com.koupper.shared.annotations.Export
import com.koupper.providers.agent.AgentMessage
import com.koupper.providers.agent.InferenceEngine
import com.koupper.providers.agent.TokenListener
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// ── Setup ─────────────────────────────────────────────────────────────────────

val home      = System.getProperty("user.home")!!
val jobsDir   = File(System.getenv("CORTEX_JOBS_DIR") ?: "$home/.koupper/jobs")
val agentsDir = File(home, ".koupper/agents").also { it.mkdirs() }
val mapper    = jacksonObjectMapper()
val http      = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

val SESSION_ID = "cortex-session"
val queueDir   = File(jobsDir, "cortex").also { it.mkdirs() }
val logDir     = File(jobsDir, "logs/cortex").also { it.mkdirs() }
val cmdInDir   = File(jobsDir, "commands/wizard").also { it.mkdirs() }
val procFile   = File(queueDir, "$SESSION_ID.json.processing")
val logFile    = File(logDir,   "$SESSION_ID.log")

logFile.writeText("")

fun ts()             = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
fun log(msg: String) = logFile.appendText("[${ts()}] $msg\n")

// Register job so monitor table shows CORTEX
procFile.writeText("""{"id":"$SESSION_ID","fileName":"CortexAgent","functionName":"cortex","scriptPath":"agents/CortexAgent.kts","sourceType":"script"}""")

// ── MCP client (calls CortexMcpServer on port 18082) ─────────────────────────

fun listMcpTools(): List<Map<String, String>> = runCatching {
    val req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:18082/mcp/tools"))
        .GET().timeout(Duration.ofSeconds(2)).build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    @Suppress("UNCHECKED_CAST")
    val body = mapper.readValue<Map<String, Any>>(resp.body())
    (body["tools"] as? List<Map<String, String>>) ?: emptyList()
}.getOrDefault(emptyList())

fun callMcpTool(toolName: String, args: Map<String, Any?>): String = runCatching {
    val payload = mapper.writeValueAsString(mapOf(
        "jsonrpc" to "2.0", "id" to 1,
        "method"  to "tools/call",
        "params"  to mapOf("name" to toolName, "arguments" to args)
    ))
    val req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:18082/"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    val tree = mapper.readTree(resp.body())
    tree.get("result")?.get("content")?.get(0)?.get("text")?.asText()
        ?: tree.get("result")?.toString()
        ?: "no result"
}.getOrElse { e -> "Error calling $toolName: ${e.message?.take(80)}" }

// ── System prompt with live tool list ────────────────────────────────────────

fun buildSystemPrompt(tools: List<Map<String, String>>): String {
    val toolLines = if (tools.isEmpty()) "  (MCP server not available)"
    else tools.joinToString("\n") { "  • ${it["name"]}: ${it["description"]}" }
    return buildString {
        appendLine("You are CORTEX, the AI orchestrator of a Koupper automation swarm.")
        appendLine("You run entirely on local LLM infrastructure — no cloud, no remote APIs.")
        appendLine()
        appendLine("AVAILABLE MCP TOOLS:")
        appendLine(toolLines)
        appendLine()
        appendLine("TOOL CALLING: When you need to use a tool, output EXACTLY this on its own line:")
        appendLine("""  CORTEX_TOOL: {"tool":"<name>","args":{<arguments>}}""")
        appendLine("You will receive: TOOL_RESULT: <json>. Continue your response after it.")
        appendLine("Only use CORTEX_TOOL when taking action. Regular answers need no prefix.")
        appendLine()
        appendLine("For agent code, wrap in a kotlin code block with '// Agent: Name' as first comment.")
        append("Be concise — terminal UI. Under 8 lines unless generating code.")
    }
}

// ── Streaming inference with tool loop ────────────────────────────────────────

fun infer(history: List<AgentMessage>, engine: InferenceEngine): String {
    val sb = StringBuilder()
    // TokenListener writes each token directly to log file — real-time streaming
    val listener = object : TokenListener {
        override fun onToken(token: String, agentId: String) {
            sb.append(token)
            logFile.appendText(token)
        }
    }
    runCatching {
        runBlocking { engine.predict<String>(history, listener = listener) }
    }.onFailure { e ->
        val err = "[Error: ${e.message?.take(80)}]"
        logFile.appendText("$err\n")
        sb.append(err)
    }
    logFile.appendText("\n")
    return sb.toString()
}

fun inferWithTools(
    history: MutableList<AgentMessage>,
    engine: InferenceEngine,
    maxIters: Int = 5
): String {
    logFile.appendText("[${ts()}] ")  // timestamp before first token
    var reply = infer(history, engine)
    var iters = 0

    while (iters < maxIters) {
        val toolLine = reply.lines().firstOrNull { it.trimStart().startsWith("CORTEX_TOOL:") } ?: break
        val jsonStr  = toolLine.trimStart().removePrefix("CORTEX_TOOL:").trim()

        val result = runCatching {
            val parsed   = mapper.readValue<Map<String, Any?>>(jsonStr)
            val toolName = parsed["tool"]?.toString() ?: return@runCatching "missing 'tool' field"
            @Suppress("UNCHECKED_CAST")
            val toolArgs = parsed["args"] as? Map<String, Any?> ?: emptyMap()
            callMcpTool(toolName, toolArgs)
        }.getOrElse { e -> "parse error: ${e.message?.take(80)}" }

        log("  ↳ ${result.take(200)}")
        log("")

        history.add(AgentMessage("assistant", reply))
        history.add(AgentMessage("user", "TOOL_RESULT: $result"))

        logFile.appendText("[${ts()}] ")
        reply = infer(history, engine)
        iters++
    }

    return reply
}

// ── Drain stale responses ─────────────────────────────────────────────────────

cmdInDir.listFiles { f -> f.name.endsWith(".response") }?.forEach { it.delete() }

// ── Main entry point ──────────────────────────────────────────────────────────

@Export
val cortex: () -> Unit = {

    val engine = try {
        app.getInstance(InferenceEngine::class)
    } catch (e: Exception) {
        log("⚠ InferenceEngine not available: ${e.message}")
        log("  Set KOUPPER_LLM_MODEL_PATH and KOUPPER_LLM_EXECUTABLE.")
        procFile.delete()
        return@cortex
    }

    val tools   = listMcpTools()
    val history = mutableListOf(AgentMessage("system", buildSystemPrompt(tools)))

    // ── Greeting ──────────────────────────────────────────────────────────────

    log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    log("  CORTEX ONLINE — Koupper InferenceEngine")
    log("  MCP tools : ${tools.size} available")
    log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

    val agentCount = agentsDir.listFiles { f -> f.name.endsWith(".kts") && f.name != "CortexAgent.kts" }?.size ?: 0
    val pending    = jobsDir.listFiles()?.flatMap { q ->
        q.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
    }?.size ?: 0

    history.add(AgentMessage("user",
        "System state: $agentCount agents deployed, $pending jobs pending. " +
        "Greet the user (2 lines max) and ask what they need built today."
    ))

    val greeting = inferWithTools(history, engine)
    history.add(AgentMessage("assistant", greeting))
    log("")
    log("  Press Enter on this job to open the command bar.")
    log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

    // ── Command loop ──────────────────────────────────────────────────────────

    val ws       = FileSystems.getDefault().newWatchService()
    val deadline = System.currentTimeMillis() + 60 * 60 * 1000L

    cmdInDir.toPath().register(ws, ENTRY_CREATE)

    while (System.currentTimeMillis() < deadline) {
        val key = ws.poll(500, TimeUnit.MILLISECONDS) ?: continue

        for (ev in key.pollEvents()) {
            if (ev.kind() == OVERFLOW) continue
            @Suppress("UNCHECKED_CAST")
            val fname = (ev as WatchEvent<Path>).context().fileName.toString()
            if (!fname.endsWith(".response")) continue

            val responseFile = File(cmdInDir, fname)
            val userMsg      = runCatching { responseFile.readText().trim() }.getOrDefault("")
            responseFile.delete()
            if (userMsg.isBlank()) continue

            log("▶ $userMsg")
            log("")

            history.add(AgentMessage("user", userMsg))
            val reply = inferWithTools(history, engine)
            history.add(AgentMessage("assistant", reply))

            // Save any generated agent scripts
            val scriptMatch = Regex("```kotlin(.*?)```", RegexOption.DOT_MATCHES_ALL).find(reply)
            if (scriptMatch != null) {
                val script    = scriptMatch.groupValues[1].trim()
                val agentName = Regex("//\\s*Agent:\\s*(.+)").find(script)
                    ?.groupValues?.get(1)?.trim()?.replace(" ", "")
                    ?: "Agent${System.currentTimeMillis() % 1000}"
                File(agentsDir, "$agentName.kts").writeText(script)
                log("[✓ Saved → ~/.koupper/agents/$agentName.kts]")
                log("[  Use run_agent to execute it]")
            }

            log("")
        }
        key.reset()
    }

    log("[!] Session expired after 1 hour.")
    procFile.delete()
    ws.close()
}
