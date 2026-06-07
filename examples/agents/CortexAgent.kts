// CortexAgent.kts — CORTEX Orchestrator (native function calling)
// Uses OpenAI-compatible function calling when available; falls back to text parsing for local models.
// Communicates via CommandBridge files and MCP server on port 18082.

import com.koupper.container.app
import com.koupper.providers.agent.AgentMessage
import com.koupper.providers.agent.InferenceEngine
import com.koupper.providers.agent.NativeToolCall
import com.koupper.providers.agent.OpenAICompatibleEngine
import com.koupper.providers.agent.ToolDefinition
import com.koupper.providers.commandbridge.CommandBridgeProvider
import com.koupper.providers.http.HtppClient
import com.koupper.providers.http.Post
import com.koupper.providers.mcp.LocalMCPClientProvider
import com.koupper.providers.mcp.LocalMCPServerProvider
import com.koupper.providers.mcp.MCPConnectedServer
import com.koupper.providers.mcp.MCPServerConfig
import com.koupper.providers.memory.MemoryProvider
import com.koupper.providers.files.fromJson
import com.koupper.providers.files.getBool
import com.koupper.providers.files.getInt
import com.koupper.providers.files.getList
import com.koupper.providers.files.getObject
import com.koupper.providers.files.getString
import com.koupper.providers.files.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ── Setup ─────────────────────────────────────────────────────────────────────

val jobsDir   = File(env("CORTEX_JOBS_DIR", "$home/.koupper/jobs"))
val agentsDir = File(home, ".koupper/agents").also { it.mkdirs() }
val http      = app.getInstance(HtppClient::class)
val memory    = runCatching { app.getInstance(MemoryProvider::class) }.getOrNull()

val SESSION_ID = "cortex-session"
val queueDir   = File(jobsDir, "cortex").also { it.mkdirs() }

// ── Multi-provider reader ─────────────────────────────────────────────────────
// Escanea K_[PROVIDER]_LLM=true en el entorno y construye engines en orden de prioridad.
// Para agregar un provider basta con setear las 5 vars en ~/.profile — sin tocar código.
fun loadLLMProviders(): List<ProviderMeta> {
    val e = System.getenv()
    return e.keys
        .filter { it.matches(Regex("K_[A-Z0-9]+_LLM")) && e[it]?.lowercase() == "true" }
        .mapNotNull { flag ->
            val pfx    = flag.removeSuffix("_LLM")
            val name   = pfx.removePrefix("K_").lowercase()
            val apiKey  = e["${pfx}_LLM_API_KEY"]              ?: ""
            val url     = e["${pfx}_LLM_URL"]                  ?: ""
            val model   = e["${pfx}_LLM_MODEL"]                ?: ""
            val prio    = e["${pfx}_LLM_PRIORITY"]?.toIntOrNull() ?: 50
            val noTools = e["${pfx}_LLM_NO_TOOLS"]?.lowercase() == "true"
            if (apiKey.isBlank() || url.isBlank() || model.isBlank()) {
                log("  ⚠ $name: K_${name.uppercase()}_LLM=true pero faltan vars (KEY/URL/MODEL) — ignorado")
                null
            } else {
                runCatching {
                    prio to ProviderMeta(
                        label        = "$name ($model)",
                        engine       = OpenAICompatibleEngine(baseUrl = url, apiKey = apiKey, model = model) as InferenceEngine,
                        baseUrl      = url,
                        model        = model,
                        apiKey       = apiKey,
                        providerName = name,
                        noTools      = noTools
                    )
                }.getOrNull()
            }
        }
        .filterNotNull()
        .sortedBy { it.first }
        .map { it.second }
}

// ── Local MCP server (bash + list_files tools on port 18082) ─────────────────
val mcpServer = runCatching {
    val srv = LocalMCPServerProvider()
    srv.registerTool("bash", "Run a shell command and return stdout",
        mapOf("type" to "object", "properties" to mapOf(
            "command" to mapOf("type" to "string", "description" to "Shell command to execute")
        ), "required" to listOf("command"))
    ) { args ->
        val cmd = args["command"]?.toString() ?: return@registerTool "missing command"
        val proc = ProcessBuilder("bash", "-c", cmd)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        out.take(4000).ifBlank { "(no output)" }
    }
    srv.registerTool("list_files", "List files in a directory",
        mapOf("type" to "object", "properties" to mapOf(
            "path" to mapOf("type" to "string", "description" to "Directory path")
        ), "required" to listOf("path"))
    ) { args ->
        val path = args["path"]?.toString() ?: return@registerTool "missing path"
        val dir = File(path.replace("~", home))
        if (!dir.exists()) return@registerTool "Directory not found: $path"
        dir.listFiles()?.joinToString("\n") { f ->
            "${if (f.isDirectory) "dir" else "file"}  ${f.name}  (${f.length()} bytes)"
        } ?: "(empty)"
    }
    srv.registerTool("job_status", "Check the status and last log lines of a job by ID",
        mapOf("type" to "object", "properties" to mapOf(
            "jobId" to mapOf("type" to "string", "description" to "Job ID, e.g. GitStatusAgent-hb-1780591778175")
        ), "required" to listOf("jobId"))
    ) { args ->
        val jobId = args["jobId"]?.toString() ?: return@registerTool "missing jobId"
        val queues = jobsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray()
        val sb = StringBuilder()

        // Check all queues for this job
        var found = false
        for (q in queues) {
            val pending    = File(q, "$jobId.json").exists()
            val processing = File(q, "$jobId.json.processing").exists()
            val failed     = File(File(q, ".failed"), "$jobId.json").exists()
            if (pending || processing || failed) {
                found = true
                val status = when { failed -> "FAILED" ; processing -> "PROCESSING" ; else -> "PENDING" }
                sb.appendLine("Status: $status (queue: ${q.name})")
            }
        }

        // Check log
        val logFile = queues.mapNotNull { q ->
            File(jobsDir, "logs/${q.name}/$jobId.log").takeIf { it.exists() }
        }.firstOrNull()

        if (logFile != null) {
            found = true
            val lines = logFile.readLines()
            val status = when {
                lines.any { "[DONE]" in it }    -> "DONE"
                lines.any { "[FAILED]" in it }  -> "FAILED"
                else                            -> "IN PROGRESS / COMPLETED"
            }
            if (sb.isEmpty()) sb.appendLine("Status: $status")
            sb.appendLine("Log (last 20 lines):")
            lines.takeLast(20).forEach { sb.appendLine("  $it") }
        }

        if (!found) "Job '$jobId' not found in any queue or log. Check the job ID." else sb.toString().trimEnd()
    }
    srv.registerTool("install_plugin",
        "Install a koupper plugin from a GitHub repo or local path. Supports MCP servers, .kts agents, and SP JARs.",
        mapOf("type" to "object", "properties" to mapOf(
            "source" to mapOf("type" to "string", "description" to "GitHub URL, 'user/repo', or absolute local path"),
            "type"   to mapOf("type" to "string", "description" to "Plugin type: auto (default), mcp, agent, sp", "enum" to listOf("auto","mcp","agent","sp"))
        ), "required" to listOf("source"))
    ) { args ->
        val source = args["source"]?.toString() ?: return@registerTool "missing 'source'"
        val type   = args["type"]?.toString() ?: "auto"
        val jobId  = "plugin-${System.currentTimeMillis()}"
        val pluginQueue = File(jobsDir, "plugin-manager").also { it.mkdirs() }
        File(pluginQueue, "$jobId.json").writeText(
            """{"source":"$source","type":"$type"}"""
        )
        val proc = ProcessBuilder("bash", "-c",
            "nohup ${System.getenv("HOME")}/.koupper/bin/koupper run ${System.getenv("HOME")}/.koupper/agents/PluginManagerAgent.kts > /dev/null 2>&1 &"
        ).start()
        proc.waitFor()
        "Plugin install started — jobId: $jobId\nTrack with: job_status(\"$jobId\")"
    }
    srv.startHttp()
    srv
}.getOrNull()
val logDir     = File(jobsDir, "logs/cortex").also { it.mkdirs() }
val cmdInDir   = File(jobsDir, "commands/wizard").also { it.mkdirs() }
val procFile   = File(queueDir, "$SESSION_ID.json.processing")
val logFile    = File(logDir,   "$SESSION_ID.log")

logFile.writeText("")

fun ts()              = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
fun log(msg: String) = logFile.appendText("[${ts()}] $msg\n")

procFile.writeText("""{"id":"$SESSION_ID","fileName":"CortexAgent","functionName":"cortex","scriptPath":"agents/CortexAgent.kts","sourceType":"script"}""")

// ── External MCP servers ──────────────────────────────────────────────────────

data class ExternalMcpServer(val client: LocalMCPClientProvider, val connected: MCPConnectedServer, val namePrefix: String)
data class ProviderMeta(val label: String, val engine: InferenceEngine, val baseUrl: String, val model: String, val apiKey: String, val providerName: String, val noTools: Boolean = false, val ctxWindow: Int = 8_192)

fun loadExternalMcpServers(): List<ExternalMcpServer> {
    val configFile = File(home, ".koupper/mcp/servers.json")
    if (!configFile.exists()) return emptyList()
    return runCatching {
        val configs = configFile.readText().fromJson<List<Map<String, Any>>>()
        configs.mapNotNull { cfg ->
            val name      = cfg["name"]?.toString() ?: return@mapNotNull null
            val transport = cfg["transport"]?.toString() ?: "http"
            val cfgArgs = (cfg["args"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val cfgEnv  = (cfg["env"] as? Map<*, *>)?.entries
                ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }
                ?.toMap() ?: emptyMap()
            val serverConfig = MCPServerConfig(
                name      = name,
                transport = transport,
                url       = cfg["url"]?.toString(),
                command   = cfg["command"]?.toString(),
                args      = cfgArgs,
                env       = cfgEnv
            )
            val client = LocalMCPClientProvider()
            runCatching {
                val connected = client.connect(serverConfig)
                log("  External MCP: $name (${connected.tools.size} tools) [$transport]")
                ExternalMcpServer(client, connected, name)
            }.onFailure { e -> log("  ⚠ Could not connect to MCP '$name': ${e.message?.take(80)}") }
            .getOrNull()
        }
    }.getOrDefault(emptyList())
}

// ── Context window resolution ─────────────────────────────────────────────────
// 4 levels: env var → API query → model-name lookup → conservative default.
// To override manually: export K_<PROVIDER>_LLM_CTX=<tokens> in ~/.profile.

val MODEL_CTX_LOOKUP = listOf(
    "claude"       to 200_000,
    "gpt-4o"       to 128_000,
    "gpt-4-turbo"  to 128_000,
    "gpt-4"        to   8_192,
    "gpt-3.5"      to  16_385,
    "o1"           to 128_000,
    "o3"           to 200_000,
    "gemini-2"     to 1_048_576,
    "gemini-1.5"   to 1_048_576,
    "gemini-1.0"   to  32_760,
    "gemini"       to 1_048_576,
    "llama-3"      to 128_000,
    "llama-2"      to   4_096,
    "mixtral"      to  32_768,
    "mistral"      to  32_768,
    "deepseek"     to  64_000,
    "qwen2"        to 128_000,
    "phi-4"        to  16_384,
    "phi-3"        to 128_000,
    "command-r"    to 128_000,
    "command"      to   4_096,
    "sonar"        to 128_000,
    "codestral"    to  32_768,
    "gemma-4"      to 128_000,
    "gemma-3"      to 128_000,
    "gemma-2"      to   8_192,
    "gemma"        to   8_192,
    "qwen3"        to 128_000,
    "qwen"         to 128_000
)

fun resolveContextWindow(meta: ProviderMeta): Int {
    // Level 1: explicit env var always wins
    val fromEnv = System.getenv("K_${meta.providerName.uppercase()}_LLM_CTX")?.toIntOrNull()
    if (fromEnv != null && fromEnv > 0) {
        log("  CTX[${meta.providerName}]: $fromEnv (env)")
        return fromEnv
    }

    // Level 2: query the provider's /v1/models/{model} endpoint
    val fromApi = runCatching {
        val url  = "${meta.baseUrl.trimEnd('/')}/v1/models/${meta.model}"
        val resp = http.get {
            this.url = url
            headers["Authorization"] = "Bearer ${meta.apiKey}"
        }
        val tree = (resp?.asString() ?: "{}").fromJson<Map<String, Any?>>()
        tree.getInt("context_window")?.takeIf { it > 0 }
            ?: tree.getInt("context_length")?.takeIf { it > 0 }
            ?: tree.getInt("max_context")?.takeIf { it > 0 }
    }.getOrNull()
    if (fromApi != null) {
        log("  CTX[${meta.providerName}]: $fromApi (API)")
        return fromApi
    }

    // Level 3: lookup by model name pattern (longest match first)
    val lowerModel = meta.model.lowercase()
    for ((pattern, ctx) in MODEL_CTX_LOOKUP) {
        if (lowerModel.contains(pattern)) {
            log("  CTX[${meta.providerName}]: $ctx (lookup: $pattern)")
            return ctx
        }
    }

    // Level 4: conservative default — add K_${meta.providerName.uppercase()}_LLM_CTX to override
    log("  CTX[${meta.providerName}]: 8192 (default — set K_${meta.providerName.uppercase()}_LLM_CTX to override)")
    return 8_192
}

// ── MCP client ────────────────────────────────────────────────────────────────

fun listMcpTools(): List<Map<String, Any>> = runCatching {
    val resp = http.get { url = "http://127.0.0.1:18082/mcp/tools" }
    val raw  = resp?.asString() ?: return@runCatching emptyList()
    @Suppress("UNCHECKED_CAST")
    val body = raw.fromJson<Map<String, Any>>()
    (body["tools"] as? List<Map<String, Any>>) ?: emptyList()
}.getOrDefault(emptyList())

fun callMcpTool(toolName: String, args: Map<String, Any?>): String = runCatching {
    val payload = mapOf("name" to toolName, "arguments" to args).toJson()
    val resp = http.post {
        url = "http://127.0.0.1:18082/mcp/call"
        headers["Content-Type"] = "application/json"
        body { json(payload) }
    }
    val tree = (resp.asString() ?: "{}").fromJson<Map<String, Any?>>()
    @Suppress("UNCHECKED_CAST")
    val result = tree["result"]
    ((result as? Map<String, Any?>)?.get("content") as? List<*>)
        ?.getOrNull(0)?.let { (it as? Map<*, *>)?.get("text")?.toString() }
        ?: result?.let { if (it is String) it else it.toJson() }
        ?: tree["error"]?.toString()
        ?: "no result"
}.getOrElse { e -> "Error calling $toolName: ${e.message?.take(80)}" }

// ── Tool definitions for native function calling ──────────────────────────────

// Normalize a property type to OpenAI-compatible types (string/integer/number/boolean/array/object).
// Converts boolean to string with enum, ensures arrays have items, drops unsupported keys.
@Suppress("UNCHECKED_CAST")
fun sanitizeProperty(prop: Map<String, Any>): Map<String, Any> {
    // Flatten anyOf/oneOf/allOf → string
    if ("anyOf" in prop || "oneOf" in prop || "allOf" in prop) {
        val r = mutableMapOf<String, Any>("type" to "string")
        prop["description"]?.let { r["description"] = it.toString() }
        return r
    }

    // Resolve type — handle nullable arrays like ["string", "null"]
    val type = when (val rawType = prop["type"]) {
        is List<*> -> rawType.filterNotNull().firstOrNull { it != "null" }?.toString() ?: "string"
        is String  -> rawType
        else       -> "string"
    }

    return when (type) {
        "array" -> {
            val rawItems = prop["items"]
            val sanitizedItems = if (rawItems is Map<*, *>)
                sanitizeProperty(rawItems as Map<String, Any>)
            else mapOf("type" to "string")
            val r = mutableMapOf<String, Any>("type" to "array", "items" to sanitizedItems)
            prop["description"]?.let { r["description"] = it.toString() }
            r
        }
        "object" -> {
            val nested = (prop["properties"] as? Map<String, Any>) ?: emptyMap()
            val r = mutableMapOf<String, Any>(
                "type" to "object",
                "properties" to nested.mapValues { (_, v) ->
                    sanitizeProperty((v as? Map<String, Any>) ?: mapOf("type" to "string"))
                }
            )
            prop["required"]?.let { r["required"] = it }
            prop["description"]?.let { r["description"] = it.toString() }
            r
        }
        else -> {
            val r = mutableMapOf<String, Any>("type" to type)
            prop["description"]?.let { r["description"] = it.toString() }
            prop["enum"]?.let { r["enum"] = it }
            r
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun sanitizeSchema(raw: Map<String, Any>): Map<String, Any> {
    val props = (raw["properties"] as? Map<String, Any>) ?: emptyMap()
    val sanitized = props.mapValues { (_, v) ->
        sanitizeProperty((v as? Map<String, Any>) ?: mapOf("type" to "string"))
    }
    val r = mutableMapOf<String, Any>("type" to "object", "properties" to sanitized)
    raw["required"]?.let { r["required"] = it }
    return r
}

@Suppress("UNCHECKED_CAST")
// Priority order per external MCP server — most useful tools first.
// When the token budget is tight, the LLM gets the highest-priority tools.
val SERVER_TOOL_PRIORITY = mapOf(
    "playwright" to listOf(
        "browser_navigate", "browser_snapshot", "browser_take_screenshot",
        "browser_click", "browser_type", "browser_fill_form", "browser_evaluate",
        "browser_hover", "browser_press_key", "browser_wait_for", "browser_handle_dialog",
        "browser_reload", "browser_navigate_back", "browser_navigate_forward",
        "browser_resize", "browser_select_option", "browser_check", "browser_uncheck",
        "browser_tabs", "browser_drag", "browser_file_upload", "browser_generate_locator",
        "browser_verify_text_visible", "browser_verify_element_visible"
    ),
    "github" to listOf(
        "search_repositories", "search_code", "get_file_contents",
        "search_issues", "list_issues", "get_issue", "list_commits",
        "list_pull_requests", "get_pull_request", "get_pull_request_files",
        "get_pull_request_status", "search_users",
        "create_issue", "create_pull_request", "push_files",
        "create_or_update_file", "fork_repository", "create_branch"
    )
)

fun buildToolDefinitions(
    localTools: List<Map<String, Any>>,
    externalServers: List<ExternalMcpServer>,
    ctxWindow: Int = 8_192
): List<ToolDefinition> {
    val defs = mutableListOf<ToolDefinition>()

    // Local tools are always included — they're small and always needed.
    for (t in localTools) {
        val name   = t["name"]?.toString() ?: continue
        val desc   = t["description"]?.toString() ?: ""
        val raw    = (t["inputSchema"] as? Map<String, Any>)
            ?: mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        defs.add(ToolDefinition(name, desc, sanitizeSchema(raw)))
    }

    // External MCP servers: include tools in priority order up to the token budget.
    // Budget = 15% of the context window (rough chars-to-tokens: /3.5).
    val tokenBudget = (ctxWindow * 0.15).toInt()
    var usedTokens  = 0

    for (srv in externalServers) {
        val priority   = SERVER_TOOL_PRIORITY[srv.namePrefix] ?: emptyList()
        val byName     = srv.connected.tools.associateBy { it.name }
        val prioritized = priority.mapNotNull { byName[it] } +
                          srv.connected.tools.filter { it.name !in priority }
        var added = 0
        for (t in prioritized) {
            val prefixed    = "${srv.namePrefix}.${t.name}"
            val desc        = t.description ?: ""
            val raw         = (t.inputSchema as? Map<String, Any>)
                ?: mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            val schema      = sanitizeSchema(raw)
            val estimated   = (prefixed.length + desc.length + schema.toJson().length) / 4 // rough ~4 chars/token, no overhead needed for relative comparison
            if (usedTokens + estimated > tokenBudget) continue
            defs.add(ToolDefinition(prefixed, desc, schema))
            usedTokens += estimated
            added++
        }
        val total = srv.connected.tools.size
        if (added < total) log("  ${srv.namePrefix}: $added/$total tools (ctx ${ctxWindow/1000}k, ~${tokenBudget}t budget)")
    }

    if (memory != null) {
        defs.add(ToolDefinition("memory.remember", "Store a fact in long-term memory",
            mapOf("type" to "object", "properties" to mapOf("text" to mapOf("type" to "string", "description" to "Fact to store")), "required" to listOf("text"))))
        defs.add(ToolDefinition("memory.recall", "Search long-term memory",
            mapOf("type" to "object", "properties" to mapOf("query" to mapOf("type" to "string"), "topK" to mapOf("type" to "integer")), "required" to listOf("query"))))
        defs.add(ToolDefinition("memory.forget", "Remove a memory entry by id",
            mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string")), "required" to listOf("id"))))
    }

    defs.add(ToolDefinition("telegram.send_photo",
        "Send an image file from the local filesystem to the current Telegram chat",
        mapOf("type" to "object",
            "properties" to mapOf(
                "path"    to mapOf("type" to "string", "description" to "Absolute path to the image file on this machine"),
                "caption" to mapOf("type" to "string", "description" to "Optional caption for the image")
            ),
            "required" to listOf("path"))))

    return defs
}

fun buildSystemPrompt(toolDefs: List<ToolDefinition>): String = buildString {
    appendLine("Eres CORTEX, un ingeniero autónomo y asistente de desarrollo que corre localmente.")
    appendLine("IDIOMA: Responde en el mismo idioma que use el usuario. Si escribe en español, responde en español. Si escribe en inglés, responde en inglés. Puedes mezclar en la misma conversación.")
    appendLine("Tienes acceso a herramientas. Úsalas de forma proactiva — no pidas permiso para crear archivos o ejecutar comandos.")
    appendLine()
    appendLine("Key tools available:")
    appendLine("  bash           — run shell commands (mkdir, npm, git, etc.)")
    appendLine("  list_files     — list directory contents")
    appendLine("  job_status     — check status and logs of a job by ID")
    appendLine("  install_plugin — install a plugin from GitHub (MCP server, agent .kts, or koupper SP)")
    appendLine("  github.*    — GitHub API: list_commits, list_pull_requests, get_repository, etc.")
    appendLine("  playwright.browser_navigate  — open a URL in the browser")
    appendLine("  playwright.browser_snapshot  — get the visible text/content of the current page")
    appendLine("  memory.*    — remember, recall, forget facts")
    appendLine()
    appendLine("TOOL USAGE RULES:")
    appendLine("  - github.* tools are ONLY for questions about code repositories, commits, PRs, and GitHub-specific content.")
    appendLine("  - For general news or current events: use playwright.browser_navigate to a news site, then playwright.browser_snapshot to read it. Do NOT use GitHub for this.")
    appendLine("  - For local file tasks: use bash.")
    appendLine("  - For RSS/news digests: the RssFeedAgent handles that — suggest running it instead of searching GitHub.")
    appendLine()
    appendLine("PROJECT SCAFFOLDING RULES:")
    appendLine("  - To create ~/projects/mi-app: cwd=~/projects, command='npm create vite@latest mi-app -- --template react-ts'")
    appendLine("  - ALWAYS use relative project name as target, parent dir as cwd — never absolute path as vite target")
    appendLine("  - Verify with 'npm run build' (not 'npm run dev' — dev server never exits)")
    appendLine("KOUPPER SCRIPTS: import com.koupper.shared.annotations.Export; @" + "Export val setup: () -> Unit = { ... }")
}

fun truncateToolResult(raw: String, maxChars: Int = 2000): String {
    if (raw.length <= maxChars) return raw
    // For JSON arrays, keep only the first few items so structure is preserved
    val trimmed = raw.trimStart()
    if (trimmed.startsWith("[")) {
        return runCatching {
            val list = raw.fromJson<List<Any>>()
            val keep = list.take(3)
            val truncated = keep.toJson()
            if (list.size > 3) "$truncated\n... (${list.size - 3} more items truncated)" else truncated
        }.getOrElse { raw.take(maxChars) + "\n...(truncated)" }
    }
    return raw.take(maxChars) + "\n...(truncated)"
}

// ── Tool execution ────────────────────────────────────────────────────────────

fun executeToolCall(name: String, args: Map<String, Any?>, externalServers: List<ExternalMcpServer>): String {
    val dotIdx = name.indexOf('.')
    return when {
        name == "telegram.send_photo" -> {
            val path    = args["path"]?.toString() ?: return "missing 'path'"
            val caption = args["caption"]?.toString() ?: ""
            val queueDir = File(home, ".koupper/jobs/telegram/photo_queue").also { it.mkdirs() }
            val req = mapOf("path" to path, "caption" to caption).toJson()
            File(queueDir, "${System.currentTimeMillis()}.json").writeText(req)
            "Photo queued for Telegram delivery: $path"
        }
        name.startsWith("memory.") && memory != null -> {
            when (name.removePrefix("memory.")) {
                "remember" -> {
                    val text = args["text"]?.toString() ?: return "missing 'text'"
                    """{"id":"${memory.remember(text)}","status":"stored"}"""
                }
                "recall" -> {
                    val query = args["query"]?.toString() ?: return "missing 'query'"
                    memory.recall(query, (args["topK"] as? Number)?.toInt() ?: 5).toJson()
                }
                "forget" -> {
                    val id = args["id"]?.toString() ?: return "missing 'id'"
                    """{"id":"$id","removed":${memory.forget(id)}}"""
                }
                else -> "Unknown memory action"
            }
        }
        dotIdx > 0 -> {
            val serverName = name.substring(0, dotIdx)
            val actualTool = name.substring(dotIdx + 1)
            val srv = externalServers.firstOrNull { it.namePrefix == serverName }
                ?: return "Unknown external MCP server: $serverName"
            srv.client.callTool(srv.connected, actualTool, args).toString()
        }
        else -> callMcpTool(name, args)
    }
}

// ── Job verification ──────────────────────────────────────────────────────────

fun waitForJob(jobId: String, queue: String, timeoutMs: Long = 300_000): String {
    val jobLog  = File(jobsDir, "logs/$queue/$jobId.log")
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!jobLog.exists() && System.currentTimeMillis() < deadline) Thread.sleep(500)
    while (System.currentTimeMillis() < deadline) {
        val content = runCatching { jobLog.readText() }.getOrDefault("")
        when {
            "[DONE]"    in content -> return "DONE"
            "[FAILED]"  in content -> return "FAILED"
            "[TIMEOUT]" in content -> return "TIMEOUT"
        }
        Thread.sleep(1_000)
    }
    return "TIMEOUT"
}

fun jobOutput(jobId: String, queue: String): String = runCatching {
    File(jobsDir, "logs/$queue/$jobId.log").readLines().takeLast(40).joinToString("\n")
}.getOrDefault("(log not found)")

fun verifyJobResult(rawResult: String): String {
    val data  = runCatching { rawResult.fromJson<Map<String, Any?>>() }.getOrNull()
    val jobId = data?.get("jobId")?.toString() ?: return rawResult
    val queue = data["queue"]?.toString() ?: "default"
    log("  ⏳ waiting for job $jobId [$queue]...")
    val status = waitForJob(jobId, queue)
    val output = jobOutput(jobId, queue)
    return when (status) {
        "DONE"   -> { log("  ✓ $jobId completed"); "JOB_DONE(jobId=$jobId)\n$output" }
        "FAILED" -> { log("  ✗ $jobId FAILED"); "JOB_FAILED(jobId=$jobId)\n$output\n\nFix the error and retry." }
        else     -> { log("  ⏱ $jobId timed out"); "JOB_TIMEOUT(jobId=$jobId)" }
    }
}

// ── Native function calling loop ──────────────────────────────────────────────

fun loadLLMRoles(): Map<String, String> =
    System.getenv().entries
        .filter { it.key.matches(Regex("K_[A-Z0-9]+_LLM_ROLE")) }
        .associate { (k, v) -> k.removePrefix("K_").removeSuffix("_LLM_ROLE").lowercase() to v.lowercase() }

val providerRoles = loadLLMRoles()

// ── Planning constants ────────────────────────────────────────────────────────

val PLANNER_PROMPT = """
You are CORTEX's Intent Analyzer. Analyze the user request and return a JSON execution plan.
Output ONLY valid JSON, no markdown, no explanation.

Schema:
{"summary":"one line","type":"process|code|explanation|architecture|conversational","risk":"low|medium|high","steps":[{"n":1,"what":"description","how":"bash|mcp|agent|llm|create_agent","detail":"specifics","risk":"low|medium|high"}],"needs_confirmation":true}

Rules:
- needs_confirmation=true if any step is high/medium risk or modifies external systems
- type=conversational and steps=[] for simple questions or greetings
- max 8 steps, be concise
""".trimIndent()

val SYNTHESIZER_PROMPT = "Synthesize these execution plans into ONE final JSON plan. Output ONLY valid JSON, no markdown."

// ── Planning functions ────────────────────────────────────────────────────────

fun classifyRole(msg: String): String {
    val m = msg.lowercase()
    val codeScore = setOf(
        "implementa", "implement", "crea", "create", "escribe", "write",
        "código", "code", "función", "function", "clase", "class",
        "refactor", "arregla", "fix", "debug", "compila", "compile", "script",
        "api", "endpoint", "módulo", "module", "build", "construye", "desarrolla", "develop",
        "test", "prueba unitaria", "unit test", "migra", "migrate"
    ).count { m.contains(it) }
    val reasonScore = setOf(
        "analiza", "analyze", "explica", "explain", "por qué", "why",
        "compara", "compare", "evalúa", "evaluate", "diseña", "design",
        "arquitectura", "architecture", "estrategia", "strategy",
        "optimiza", "optimize", "investiga", "investigate", "pros", "contras",
        "razona", "piensa", "think deeply", "qué recomiendas", "what do you recommend"
    ).count { m.contains(it) }
    val fastScore = setOf(
        "qué es", "what is", "qué significa", "cuándo", "when",
        "dónde", "where", "quién", "who", "lista", "list",
        "muestra", "show", "estado", "status", "resume", "summarize",
        "traduce", "translate", "cuántos", "how many"
    ).count { m.contains(it) }

    return when {
        codeScore > 0 && codeScore >= reasonScore -> "code"
        reasonScore > 0                           -> "reasoning"
        fastScore > 0 && msg.split(" ").size < 8  -> "fast"
        else                                      -> "general"
    }
}

fun engineForRole(role: String, providers: List<ProviderMeta>): ProviderMeta =
    providers.firstOrNull { providerRoles[it.providerName] == role }
        ?: providers.firstOrNull { providerRoles[it.providerName] == "general" }
        ?: providers.first()

fun isComplexRequest(msg: String): Boolean {
    val m = msg.lowercase()
    val words = m.split(Regex("\\s+")).size
    if (words <= 4) return false
    val multiStep = listOf("y luego", "y después", "también", "además", "then", "after that", "paso a paso", "y también")
    if (multiStep.any { m.contains(it) }) return true
    val actionWords = listOf("deploy", "crea", "create", "build", "construye", "analiza", "analyze",
        "arregla", "fix", "refactor", "instala", "install", "configura", "configure",
        "monitorea", "monitor", "verifica", "check", "ejecuta", "run", "prueba", "test",
        "push", "merge", "commit", "arquitectura", "architecture", "diseña", "design",
        "implementa", "implement", "migra", "migrate", "optimiza", "optimize", "despliega")
    val count = actionWords.count { m.contains(it) }
    return count >= 2 || (count >= 1 && words > 12)
}

fun extractPlanJson(text: String): String {
    val fenced = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```").find(text)?.groupValues?.get(1)
    if (fenced != null) return fenced.trim()
    return Regex("\\{[\\s\\S]*\\}").find(text)?.value?.trim() ?: text.trim()
}

fun buildPlan(userMsg: String, engines: List<ProviderMeta>): String {
    val fallback = """{"summary":"${userMsg.take(60).replace("\"","")}","type":"conversational","risk":"low","steps":[],"needs_confirmation":false}"""
    if (engines.isEmpty()) return fallback

    val msgs = listOf(AgentMessage("system", PLANNER_PROMPT), AgentMessage("user", userMsg))

    val proposals = if (engines.size == 1) {
        listOf(runCatching { runBlocking { engines.first().engine.predict<String>(msgs) }.toString() }.getOrNull())
    } else {
        runBlocking {
            engines.take(3).map { meta ->
                async(Dispatchers.IO) { runCatching { meta.engine.predict<String>(msgs) }.getOrNull()?.toString() }
            }.awaitAll()
        }
    }.filterNotNull().filter { it.isNotBlank() }

    if (proposals.isEmpty()) return fallback
    if (proposals.size == 1) return extractPlanJson(proposals.first())

    val synthMsgs = listOf(
        AgentMessage("system", SYNTHESIZER_PROMPT),
        AgentMessage("user", "Plans:\n${proposals.joinToString("\n---\n")}\nRequest: $userMsg")
    )
    val synthesis = runCatching { runBlocking { engines.first().engine.predict<String>(synthMsgs) }.toString() }
        .getOrNull() ?: proposals.first()
    return extractPlanJson(synthesis)
}

fun formatPlan(planJson: String): String = runCatching {
    val p = planJson.fromJson<Map<String, Any?>>()
    val sb = StringBuilder()
    sb.appendLine("┌──────────────────────────────────────────┐")
    sb.appendLine("│   PLAN DE EJECUCIÓN                      │")
    sb.appendLine("└──────────────────────────────────────────┘")
    sb.appendLine("  Objetivo : ${p.getString("summary") ?: "?"}")
    sb.appendLine("  Tipo     : ${p.getString("type") ?: "?"}")
    val riskLabel = when (p.getString("risk") ?: "low") { "high" -> "⚠  ALTO" ; "medium" -> "⚡ MEDIO" ; else -> "✓  BAJO" }
    sb.appendLine("  Riesgo   : $riskLabel")
    val steps = p.getList("steps")
    if (!steps.isNullOrEmpty()) {
        sb.appendLine("")
        steps.forEach { step ->
            @Suppress("UNCHECKED_CAST")
            val s = step as? Map<String, Any?> ?: return@forEach
            val mark = when (s.getString("risk") ?: "low") { "high" -> " ⚠" ; "medium" -> " ⚡" ; else -> "" }
            sb.appendLine("  ${s.getInt("n") ?: 0}. ${s.getString("what") ?: "?"}$mark")
            sb.appendLine("     [${s.getString("how") ?: "?"}] ${s.getString("detail")?.take(80) ?: ""}")
        }
    } else {
        sb.appendLine("  (respuesta directa — sin pasos de ejecución)")
    }
    sb.toString().trimEnd()
}.getOrDefault("Plan: $planJson")

fun buildContextMsg(userMsg: String, base: String = userMsg): String {
    if (memory == null) return base
    val recalls = runCatching { memory.recall(userMsg, topK = 3) }.getOrDefault(emptyList())
    return if (recalls.isNotEmpty()) "[CONTEXT:\n${recalls.joinToString("\n") { "- ${it.text.take(400)}" }}]\n$base" else base
}

fun memoryFallback(query: String): String {
    if (memory != null) {
        val recalls = runCatching { memory.recall(query, topK = 3) }.getOrDefault(emptyList())
        if (recalls.isNotEmpty()) {
            val ctx = recalls.joinToString("\n") { "• ${it.text.take(300)}" }
            return "📚 (LLM no disponible — respondiendo desde memoria)\n\n$ctx"
        }
    }
    return "⚠ Estoy teniendo dificultades de comunicación en este momento. El servidor LLM no está disponible. Intenta más tarde o verifica que el servidor esté encendido."
}

fun inferWithNativeTools(
    history: MutableList<AgentMessage>,
    engine: InferenceEngine,
    toolDefs: List<ToolDefinition>,
    externalServers: List<ExternalMcpServer>,
    maxIters: Int = 20
): String {
    var iters = 0
    var lastText = ""

    while (iters < maxIters) {
        logFile.appendText("[${ts()}] ")
        val result = runCatching {
            runBlocking { engine.predictWithTools(history, toolDefs) }
        }.getOrElse { e ->
            val err = "[Error: ${e.message?.take(100)}]"
            logFile.appendText("$err\n")
            return err
        }

        lastText = result.text

        if (result.toolCalls.isEmpty()) {
            if (lastText.isNotBlank()) logFile.appendText(lastText)
            logFile.appendText("\n")
            break
        }

        if (lastText.isNotBlank()) logFile.appendText(lastText)
        logFile.appendText("\n")

        // Add assistant message with ALL tool calls in one batch
        history.add(AgentMessage(
            role             = "assistant",
            content          = lastText,
            nativeToolCalls  = result.toolCalls
        ))

        // Execute all tool calls and add tool result messages
        for (tc in result.toolCalls) {
            log("  → ${tc.name}(${tc.arguments.toJson().take(120)})")
            val rawResult = runCatching {
                executeToolCall(tc.name, tc.arguments, externalServers)
            }.getOrElse { e -> "Error: ${e.message?.take(80)}" }

            val toolResult = if (tc.name == "run_agent") verifyJobResult(rawResult) else rawResult
            val contextResult = truncateToolResult(toolResult)
            log("  ↳ ${toolResult.take(200)}")
            log("")

            // Tool result message — role="tool", tool_call_id matches the tc.id
            history.add(AgentMessage(
                role     = "tool",
                content  = contextResult,
                toolCall = com.koupper.providers.agent.ToolCall(
                    toolName  = tc.name,
                    action    = tc.id,   // tool_call_id
                    arguments = tc.arguments
                )
            ))
        }

        iters++
    }

    return lastText
}

// ── Main entry point ──────────────────────────────────────────────────────────

@Export
val setup: () -> Unit = {

    val providers = loadLLMProviders()

    if (providers.isEmpty()) {
        log("⚠ Sin providers LLM. Configura K_[PROVIDER]_LLM=true en ~/.profile")
        procFile.delete()
    }

    if (providers.isNotEmpty()) {

        val localTools      = listMcpTools()
        val externalServers = loadExternalMcpServers()

        // Resolve context window for every provider at startup (env → API → lookup → default)
        val resolvedProviders = providers.map { p -> p.copy(ctxWindow = resolveContextWindow(p)) }
        val primary           = resolvedProviders.first()
        val ctxWindow         = primary.ctxWindow

        // Pre-build a tool list sized for each provider — no recalculation at runtime
        val toolDefsByProvider = resolvedProviders.associateWith { p ->
            buildToolDefinitions(localTools, externalServers, p.ctxWindow)
        }
        val toolDefs  = toolDefsByProvider[primary]!!
        val history   = mutableListOf(AgentMessage("system", buildSystemPrompt(toolDefs)))

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("  CORTEX ONLINE")
        log("  Providers: ${resolvedProviders.joinToString(" → ") { "${it.label} (${it.ctxWindow/1000}k)" }}")
        log("  CTX: ${ctxWindow/1000}k tokens")
        log("  Tools: ${toolDefs.size} (${localTools.size} MCP + ${externalServers.sumOf { it.connected.tools.size }} external${if (memory != null) " + 3 memory" else ""})")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val agentCount = agentsDir.listFiles { f -> f.name.endsWith(".kts") && f.name != "CortexAgent.kts" }?.size ?: 0
        val pending    = jobsDir.listFiles()?.flatMap { q ->
            q.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
        }?.size ?: 0

        history.add(AgentMessage("user",
            "System state: $agentCount agents deployed, $pending jobs pending. " +
            "Saluda al usuario en 1-2 líneas en español y pregunta qué quiere construir hoy."
        ))

        val greeting = runCatching {
            runBlocking { primary.engine.predict<String>(history) }
        }.getOrDefault("CORTEX ready. What do you want to build?")
        history.add(AgentMessage("assistant", greeting))
        logFile.appendText("[${ts()}] $greeting\n")
        log("")
        log("  Press Enter on this job to open the command bar.")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val bridge   = app.getInstance(CommandBridgeProvider::class)
        val deadline = System.currentTimeMillis() + 8 * 60 * 60 * 1000L

        bridge.watch(cmdInDir).drain()

        val recentCmds  = mutableMapOf<Int, Long>()

        // Planning engines: prefer fast/reasoning roles; fall back to all providers
        val planningEngines = providers.filter { p ->
            providerRoles[p.label.substringBefore(" ")] in listOf("fast", "reasoning")
        }.ifEmpty { providers }

        val confirmWords = setOf("si", "sí", "yes", "s", "y", "ok", "dale", "adelante", "ejecuta", "confirmo", "confirmar")
        val cancelWords  = setOf("no", "cancel", "cancelar", "abort", "abortar", "detener")

        // Plan confirmation state
        var pendingPlan    : String? = null
        var pendingRequest : String? = null

        while (System.currentTimeMillis() < deadline) {
            val userMsg = bridge.nextCommand() ?: continue

            // Dedup only for new requests, not confirmations
            if (pendingPlan == null) {
                val now  = System.currentTimeMillis()
                val hash = userMsg.trimEnd().hashCode()
                recentCmds.entries.removeIf { now - it.value > 2_000L }
                if (hash in recentCmds) continue
                recentCmds[hash] = now
            }

            var shouldExecute    = false
            var isConversational = false
            var routingRequest   = userMsg  // original request for LLM routing (not "si"/"dale")

            if (pendingPlan != null && pendingRequest != null) {
                // ── Confirmation response ─────────────────────────────────────
                val answer = userMsg.lowercase().trim()
                when {
                    answer in confirmWords -> {
                        log("  ✓ Plan aprobado — ejecutando...")
                        log("")
                        routingRequest = pendingRequest!!  // route based on original request
                        val execMsg = "[PLAN APROBADO. Ejecuta EXACTAMENTE estos pasos en orden:\n$pendingPlan]\n\nSolicitud original: $pendingRequest"
                        history.add(AgentMessage("user", buildContextMsg(pendingRequest!!, execMsg)))
                        pendingPlan    = null
                        pendingRequest = null
                        shouldExecute  = true
                    }
                    answer in cancelWords -> {
                        log("  ✗ Plan cancelado.")
                        log("")
                        pendingPlan    = null
                        pendingRequest = null
                    }
                    else -> {
                        // Feedback → re-plan
                        log("  ↺ Replanificando con tu feedback...")
                        val revised = "${pendingRequest!!} [ajuste solicitado: $userMsg]"
                        val newPlan = buildPlan(revised, planningEngines)
                        log(formatPlan(newPlan))
                        log("")
                        log("  ¿Ejecutar este plan? (si / no / más comentarios)")
                        pendingPlan    = newPlan
                        pendingRequest = revised
                    }
                }
            } else {
                // ── New request ───────────────────────────────────────────────
                log("▶ $userMsg")
                log("")

                if (isComplexRequest(userMsg)) {
                    log("  ◈ Analizando solicitud con ${planningEngines.size} provider(s)...")
                    val plan = buildPlan(userMsg, planningEngines)
                    log(formatPlan(plan))
                    log("")

                    val planNode = runCatching { plan.fromJson<Map<String, Any?>>() }.getOrNull()
                    isConversational = planNode?.getString("type") == "conversational"
                                    || (planNode?.getList("steps")?.size ?: 0) == 0
                    val needsConfirmation = !isConversational &&
                                           (planNode?.getBool("needs_confirmation") != false)

                    if (needsConfirmation) {
                        log("  ¿Ejecutar este plan? (si / no / comentarios para ajustar)")
                        pendingPlan    = plan
                        pendingRequest = userMsg
                    } else {
                        val base = if (isConversational) userMsg else "[PLAN:\n$plan]\n\n$userMsg"
                        history.add(AgentMessage("user", buildContextMsg(userMsg, base)))
                        shouldExecute = true
                    }
                } else {
                    // No action keywords → no tools needed, plain inference
                    isConversational = true
                    history.add(AgentMessage("user", buildContextMsg(userMsg)))
                    shouldExecute = true
                }
            }

            if (shouldExecute) {
                val historyMark  = history.size
                val role         = classifyRole(routingRequest)
                val activeEngine = engineForRole(role, resolvedProviders)
                if (activeEngine.providerName != resolvedProviders.first().providerName)
                    log("  → ${activeEngine.label} ($role)")

                // For conversational turns, strip tool defs from system prompt to save context
                val inferHistory: MutableList<AgentMessage> = if (isConversational) {
                    val slim = mutableListOf(AgentMessage("system",
                        "You are CORTEX, a concise AI assistant and orchestrator. " +
                        "Eres CORTEX, asistente conciso. Responde en el mismo idioma que el usuario. Fecha: ${java.time.LocalDate.now()}. /no_think"))
                    slim.addAll(history.drop(1))
                    slim
                } else history

                fun infer(meta: ProviderMeta): String {
                    if (isConversational || meta.noTools)
                        return runCatching { runBlocking { meta.engine.predict<String>(inferHistory) } }
                            .getOrElse { e -> "[Error: ${e.message?.take(100)}]" }
                    val provTools = toolDefsByProvider[meta] ?: toolDefs
                    return inferWithNativeTools(history, meta.engine, provTools, externalServers)
                }

                var reply = infer(activeEngine)

                if (reply.startsWith("[Error:")) {
                    var recovered = false
                    for (fb in resolvedProviders.drop(1)) {
                        while (history.size > historyMark) history.removeAt(history.size - 1)
                        log("  ↺ Primary LLM failed — trying ${fb.label}")
                        reply = infer(fb)
                        if (!reply.startsWith("[Error:")) { recovered = true; break }
                    }
                    if (!recovered) {
                        while (history.size > historyMark) history.removeAt(history.size - 1)
                        reply = memoryFallback(userMsg)
                        log(reply)
                    }
                }

                // Log conversational replies (inferWithNativeTools logs internally)
                if (isConversational && !reply.startsWith("[Error:")) log(reply)

                history.add(AgentMessage("assistant", reply))

                // Trim history: keep system[0] + last 20 messages to prevent context overflow
                if (history.size > 21) {
                    val sys  = history[0]
                    val tail = history.takeLast(20)
                    history.clear()
                    history.add(sys)
                    history.addAll(tail)
                }

                log("")
            }
        }

        log("[!] Session ended.")
        bridge.close()
    }
    procFile.delete()
}
