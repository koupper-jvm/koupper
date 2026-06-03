// CortexAgent.kts — CORTEX Orchestrator
// Uses Koupper's InferenceEngine SP (LlamaServerSidecar, SSE streaming).
// Communicates via CommandBridge files and MCP server on port 18082.
//
// Required env vars:
//   KOUPPER_LLM_MODEL_PATH   — path to .gguf model file
//   KOUPPER_LLM_EXECUTABLE   — path to llama-server binary (default: llama-server)
//   CORTEX_JOBS_DIR          — jobs dir (default: ~/.koupper/jobs)

import com.koupper.container.app
import com.koupper.providers.agent.AgentMessage
import com.koupper.providers.agent.InferenceEngine
import com.koupper.providers.agent.TokenListener
import com.koupper.providers.mcp.MCPClientProvider
import com.koupper.providers.mcp.MCPConnectedServer
import com.koupper.providers.mcp.MCPServerConfig
import com.koupper.providers.mcp.MCPToolDescriptor
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

val jobsDir   = File(env("CORTEX_JOBS_DIR", "$home/.koupper/jobs"))
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

fun ts() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

// emit() writes to the session log file watched by the TUI monitor.
// Named 'emit' to avoid collision with the preamble's 'val log: KLogger'.
fun emit(msg: String) = logFile.appendText("[${ts()}] $msg\n")

// Register job so monitor table shows CORTEX
procFile.writeText("""{"id":"$SESSION_ID","fileName":"CortexAgent","functionName":"cortex","scriptPath":"agents/CortexAgent.kts","sourceType":"script"}""")

// ── External MCP servers ──────────────────────────────────────────────────────
// Config: ~/.koupper/mcp/servers.json
// Format: [{"name":"playwright","transport":"stdio","command":"npx","args":["@playwright/mcp"]},
//          {"name":"github","transport":"http","url":"http://localhost:3001"}]

data class ExternalMcpServer(val connected: MCPConnectedServer, val namePrefix: String)

fun loadExternalMcpServers(): List<ExternalMcpServer> {
    val configFile = File(home, ".koupper/mcp/servers.json")
    if (!configFile.exists()) return emptyList()

    val client = runCatching { app.getInstance(MCPClientProvider::class) }.getOrNull() ?: return emptyList()

    return runCatching {
        val configs = mapper.readValue<List<Map<String, Any>>>(configFile)
        configs.mapNotNull { cfg ->
            val name      = cfg["name"]?.toString() ?: return@mapNotNull null
            val transport = cfg["transport"]?.toString() ?: "http"
            @Suppress("UNCHECKED_CAST")
            val serverConfig = MCPServerConfig(
                name      = name,
                transport = transport,
                url       = cfg["url"]?.toString(),
                command   = cfg["command"]?.toString(),
                args      = (cfg["args"] as? List<String>) ?: emptyList(),
                env       = (cfg["env"] as? Map<String, String>) ?: emptyMap()
            )
            runCatching {
                val connected = client.connect(serverConfig)
                emit("  External MCP: $name (${connected.tools.size} tools) [$transport]")
                ExternalMcpServer(connected, name)
            }.onFailure { e -> emit("  ⚠ Could not connect to MCP '$name': ${e.message?.take(60)}") }
            .getOrNull()
        }
    }.getOrDefault(emptyList())
}

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

fun buildSystemPrompt(
    localTools: List<Map<String, String>>,
    externalServers: List<ExternalMcpServer>
): String {
    val localLines = if (localTools.isEmpty()) "  (none)"
    else localTools.joinToString("\n") { "  • ${it["name"]}: ${it["description"]}" }

    val externalLines = if (externalServers.isEmpty()) ""
    else "\nEXTERNAL MCP TOOLS (prefix: serverName.toolName):\n" +
        externalServers.joinToString("\n") { srv ->
            srv.connected.tools.joinToString("\n") { t ->
                "  • ${srv.namePrefix}.${t.name}: ${t.description}"
            }
        }

    return buildString {
        appendLine("You are CORTEX, the AI orchestrator of a Koupper automation swarm.")
        appendLine("You run entirely on local LLM infrastructure — no cloud, no remote APIs.")
        appendLine()
        appendLine("BUILT-IN TOOLS:")
        appendLine(localLines)
        if (externalLines.isNotBlank()) append(externalLines)
        appendLine()
        appendLine("TOOL CALLING: When you need to use a tool, output EXACTLY this on its own line:")
        appendLine("""  CORTEX_TOOL: {"tool":"<name>","args":{<arguments>}}""")
        appendLine("For external tools use the prefix: playwright.screenshot, github.create_pr, etc.")
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
    // TokenListener writes each token directly to logFile — real-time streaming to TUI
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
    externalServers: List<ExternalMcpServer> = emptyList(),
    maxIters: Int = 5
): String {
    logFile.appendText("[${ts()}] ")
    var reply = infer(history, engine)
    var iters = 0

    while (iters < maxIters) {
        val toolLine = reply.lines().firstOrNull { it.trimStart().startsWith("CORTEX_TOOL:") } ?: break
        val jsonStr  = toolLine.trimStart().removePrefix("CORTEX_TOOL:").trim()

        val result = runCatching {
            val parsed   = mapper.readValue<Map<String, Any?>>(jsonStr)
            val fullName = parsed["tool"]?.toString() ?: return@runCatching "missing 'tool' field"
            @Suppress("UNCHECKED_CAST")
            val toolArgs = parsed["args"] as? Map<String, Any?> ?: emptyMap()

            val dotIdx = fullName.indexOf('.')
            if (dotIdx > 0) {
                val serverName = fullName.substring(0, dotIdx)
                val actualTool = fullName.substring(dotIdx + 1)
                val srv = externalServers.firstOrNull { it.namePrefix == serverName }
                    ?: return@runCatching "Unknown external MCP server: $serverName"
                val mcpClient = app.getInstance(MCPClientProvider::class)
                mcpClient.callTool(srv.connected, actualTool, toolArgs).toString()
            } else {
                callMcpTool(fullName, toolArgs)
            }
        }.getOrElse { e -> "error: ${e.message?.take(80)}" }

        emit("  ↳ ${result.take(200)}")
        emit("")

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

    val engine = runCatching { app.getInstance(InferenceEngine::class) }.getOrElse { e ->
        emit("⚠ InferenceEngine not available: ${e.message}")
        emit("  Set KOUPPER_LLM_MODEL_PATH and KOUPPER_LLM_EXECUTABLE.")
        procFile.delete()
        null
    }

    if (engine != null) {
        val localTools      = listMcpTools()
        val externalServers = loadExternalMcpServers()
        val history = mutableListOf(AgentMessage("system", buildSystemPrompt(localTools, externalServers)))

        emit("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        emit("  CORTEX ONLINE — Koupper InferenceEngine")
        emit("  Built-in tools : ${localTools.size}")
        emit("  External MCPs  : ${externalServers.size} servers")
        emit("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val agentCount = agentsDir.listFiles { f -> f.name.endsWith(".kts") && f.name != "CortexAgent.kts" }?.size ?: 0
        val pending    = jobsDir.listFiles()?.flatMap { q ->
            q.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
        }?.size ?: 0

        history.add(AgentMessage("user",
            "System state: $agentCount agents deployed, $pending jobs pending. " +
            "Greet the user (2 lines max) and ask what they need built today."
        ))

        val greeting = inferWithTools(history, engine, externalServers)
        history.add(AgentMessage("assistant", greeting))
        emit("")
        emit("  Press Enter on this job to open the command bar.")
        emit("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

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

                emit("▶ $userMsg")
                emit("")

                history.add(AgentMessage("user", userMsg))
                val reply = inferWithTools(history, engine, externalServers)
                history.add(AgentMessage("assistant", reply))

                val scriptMatch = Regex("```kotlin(.*?)```", RegexOption.DOT_MATCHES_ALL).find(reply)
                if (scriptMatch != null) {
                    val script    = scriptMatch.groupValues[1].trim()
                    val agentName = Regex("//\\s*Agent:\\s*(.+)").find(script)
                        ?.groupValues?.get(1)?.trim()?.replace(" ", "")
                        ?: "Agent${System.currentTimeMillis() % 1000}"
                    File(agentsDir, "$agentName.kts").writeText(script)
                    emit("[✓ Saved → ~/.koupper/agents/$agentName.kts]")
                    emit("[  Use run_agent to execute it]")
                }
                emit("")
            }
            key.reset()
        }

        emit("[!] Session expired after 1 hour.")
        ws.close()
    }
    procFile.delete()
}
