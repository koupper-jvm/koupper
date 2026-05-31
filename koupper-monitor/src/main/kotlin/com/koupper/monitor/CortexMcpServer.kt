package com.koupper.monitor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.providers.mcp.MCPServerProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Registers all Cortex tools into a MCPServerProvider instance.
// The caller is responsible for starting and stopping the HTTP server.
internal fun registerCortexTools(mcp: MCPServerProvider, jobsDir: File, agentsDir: File) {
    val mapper = jacksonObjectMapper()

    fun strP(desc: String) = mapOf("type" to "string", "description" to desc)
    fun intP(desc: String) = mapOf("type" to "integer", "description" to desc)
    fun arrP(desc: String) = mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to desc)
    fun obj(vararg entries: Pair<String, Any>) = mapOf(*entries)
    fun err(msg: String): Map<String, Any> = mapOf("error" to msg)
    fun mcpTs() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    fun queueDirs() = jobsDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in setOf("logs", "commands") }
        ?: emptyList()

    fun findJobStatus(jobId: String): Map<String, Any> {
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

    fun readJobLog(jobId: String, tail: Int): Map<String, Any> {
        val logFile = File(jobsDir, "logs").walkTopDown()
            .firstOrNull { it.name == "$jobId.log" }
        return if (logFile != null && logFile.exists()) {
            val lines = logFile.readLines().takeLast(tail)
            mapOf("jobId" to jobId, "lines" to lines, "count" to lines.size)
        } else {
            mapOf("jobId" to jobId, "lines" to emptyList<String>(), "error" to "log not found")
        }
    }

    fun inspectSwarm(): Map<String, Any> {
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
            "queues"     to queues,
            "totals"     to mapOf("pending" to pending, "processing" to processing, "failed" to failed),
            "agentStore" to mapOf("count" to agentCount, "path" to agentsDir.absolutePath)
        )
    }

    fun cancelJob(jobId: String): Map<String, Any> {
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

    fun buildPipelineScript(pipelineId: String, stages: List<String>): String {
        val stageList = stages.joinToString(",")
        val stageVals = stages.joinToString("\n\n") { name ->
            """val stage_$name: () -> Unit = {
    println("[PIPELINE:STAGE:$name:START]")
    val t0 = System.currentTimeMillis()
    val exit = ProcessBuilder("${'$'}home/.koupper/bin/koupper", "run", "${'$'}home/.koupper/agents/$name.kts")
        .inheritIO().start().waitFor()
    val elapsed = System.currentTimeMillis() - t0
    if (exit != 0) {
        println("[PIPELINE:STAGE:$name:FAILED:${'$'}elapsed]")
        println("[PIPELINE:FAILED:$name]")
        error("Stage $name failed (exit ${'$'}exit)")
    }
    println("[PIPELINE:STAGE:$name:DONE:${'$'}elapsed]")
}"""
        }
        val pipelineList = stages.mapIndexed { idx, name ->
            if (idx == 0) "::stage_$name"
            else "::stage_$name.dependsOn(::stage_${stages[idx - 1]})"
        }.joinToString(",\n    ")

        return """
// $pipelineId.kts — Auto-generated pipeline coordinator
// Stages: ${stages.joinToString(" → ")}
// Uses ScriptExecutor.runPipeline with dependsOn — Koupper pipeline API

import com.koupper.octopus.ScriptExecutor
import com.koupper.shared.octopus.dependsOn
import java.io.File

val home = System.getProperty("user.home")

println("[PIPELINE:START:$stageList]")

$stageVals

ScriptExecutor.runPipeline(
    listOf(
    $pipelineList
    ),
    async = false
) { report ->
    println("[PIPELINE:DONE]")
    println("Total: ${'$'}{report.totalMs}ms  OK: ${'$'}{report.okCount}/${'$'}{report.steps.size}")
}
""".trimIndent()
    }

    // ── Swarm script generator ────────────────────────────────────────────────

    fun buildSwarmScript(swarmId: String, agents: List<Map<String, Any?>>): String {
        val agentNames = agents.joinToString(" → ") { it["name"]?.toString() ?: "Agent" }
        val agentDefs  = agents.joinToString(",\n        ") { cfg ->
            val name = cfg["name"]?.toString()?.replace("\"", "\\\"") ?: "Agent"
            val role = cfg["role"]?.toString()?.replace("\"", "\\\"") ?: "Specialist"
            val goal = cfg["goal"]?.toString()?.replace("\"", "\\\"") ?: "Complete the assigned task"
            val task = cfg["task"]?.toString()?.replace("\"", "\\\"") ?: "Execute your role"
            """agent {
            name = "$name"
            role {
                identity = "$role"
                goal = "$goal"
                instructions = "Use context from previous agents when available. Be concise."
            }
            task<String> { prompt = "$task" }
        }"""
        }

        return """
// $swarmId.kts — Auto-generated swarm coordinator
// Agents: $agentNames
// Uses Koupper's SwarmCoordinator.runSequence() with result handoff

import com.koupper.container.app
import com.koupper.providers.agent.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val home    = System.getProperty("user.home")!!
val logFile = File("${'$'}home/.koupper/jobs/logs/swarm/$swarmId.log")
        .also { it.parentFile.mkdirs() }
fun log(msg: String) = logFile.appendText(
    "${'$'}{LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))} ${'$'}msg\n"
)

log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
log("  SWARM: $swarmId")
log("  Agents: ${agents.size} — $agentNames")
log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

val coordinator = app.getInstance(SwarmCoordinator::class)

val agentConfigs = listOf(
    $agentDefs
)

val results = runBlocking { coordinator.runSequence(agentConfigs) }

results.forEachIndexed { i, instance ->
    val statusStr = when (val s = instance.state) {
        is AgentState.Idle   -> "DONE"
        is AgentState.Failed -> "FAILED: ${'$'}{s.error}"
        else                  -> "UNKNOWN"
    }
    log("  [${"\${i + 1}"}] ${'$'}{instance.config.name}: ${'$'}statusStr")
    instance.result?.let { log("      → ${'$'}{it.toString().take(300)}") }
}

log("  SWARM COMPLETE")
""".trimIndent()
    }

    // ── Tool registrations ────────────────────────────────────────────────────

    // 1 ─ list_agents
    mcp.registerTool(
        "list_agents",
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

    // 2 ─ run_agent
    mcp.registerTool(
        "run_agent",
        "Submit an agent from the store to a job queue. ONLY agents that already exist in ~/.koupper/agents/ can be run — no arbitrary code execution.",
        obj("type" to "object",
            "properties" to mapOf(
                "name"  to strP("Agent name without .kts extension"),
                "queue" to strP("Queue name (default: 'default')"),
                "args"  to strP("Optional JSON string of arguments to pass to the agent")
            ),
            "required" to listOf("name"))
    ) { args ->
        val name    = args["name"]?.toString()  ?: return@registerTool err("name is required")
        val queue   = args["queue"]?.toString() ?: "default"
        val jobArgs = args["args"]?.toString()?.trim()?.takeIf { it.startsWith("{") } ?: "{}"

        val agentFile = File(agentsDir, "$name.kts")
        if (!agentFile.exists()) {
            val available = agentsDir.listFiles { f -> f.name.endsWith(".kts") }
                ?.map { it.nameWithoutExtension } ?: emptyList()
            return@registerTool err("Agent '$name' not found in agent store. Available: $available")
        }

        val jobId = "$name-${System.currentTimeMillis()}"
        val qDir  = File(jobsDir, queue).also { it.mkdirs() }
        File(qDir, "$jobId.json").writeText(
            """{"id":"$jobId","fileName":"$name","functionName":"run","scriptPath":"agents/$name.kts","sourceType":"script","args":$jobArgs,"submittedAt":"${mcpTs()}"}"""
        )
        mapOf("ok" to true, "jobId" to jobId, "queue" to queue, "agent" to name)
    }

    // 3 ─ job_status
    mcp.registerTool(
        "job_status",
        "Get the current status of a job by its ID",
        obj("type" to "object",
            "properties" to mapOf("jobId" to strP("Job ID to check")),
            "required" to listOf("jobId"))
    ) { args ->
        val id = args["jobId"]?.toString() ?: return@registerTool err("jobId is required")
        findJobStatus(id)
    }

    // 4 ─ read_log
    mcp.registerTool(
        "read_log",
        "Read the output log of a job (last N lines)",
        obj("type" to "object",
            "properties" to mapOf(
                "jobId" to strP("Job ID to read logs for"),
                "tail"  to intP("Number of lines from the end to return (default: 30)")
            ),
            "required" to listOf("jobId"))
    ) { args ->
        val id   = args["jobId"]?.toString()          ?: return@registerTool err("jobId is required")
        val tail = (args["tail"] as? Number)?.toInt() ?: 30
        readJobLog(id, tail)
    }

    // 5 ─ inspect_swarm
    mcp.registerTool(
        "inspect_swarm",
        "Get a full snapshot of the swarm: all queues, job counts per state, and agent store size",
        obj("type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>())
    ) { _ -> inspectSwarm() }

    // 6 ─ create_agent
    mcp.registerTool(
        "create_agent",
        "Save a generated agent script to the agent store. This is step 1. Use run_agent to execute it (step 2).",
        obj("type" to "object",
            "properties" to mapOf(
                "name"    to strP("Agent name without .kts extension (alphanumeric, dash, underscore only)"),
                "content" to strP("Full .kts script content")
            ),
            "required" to listOf("name", "content"))
    ) { args ->
        val name    = args["name"]?.toString()    ?: return@registerTool err("name is required")
        val content = args["content"]?.toString() ?: return@registerTool err("content is required")
        val safe    = name.replace(Regex("[^a-zA-Z0-9_-]"), "")
        if (safe.isEmpty()) return@registerTool err("invalid agent name — use alphanumeric, dash, or underscore only")
        agentsDir.mkdirs()
        File(agentsDir, "$safe.kts").writeText(content)
        mapOf("ok" to true, "saved" to "~/.koupper/agents/$safe.kts", "next" to "Use run_agent {\"name\":\"$safe\"} to execute it")
    }

    // 7 ─ pipeline_run
    mcp.registerTool(
        "pipeline_run",
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
            ?: return@registerTool err("stages must be a list of agent names")
        val queue = args["queue"]?.toString() ?: "pipeline"

        val missing = stages.filter { !File(agentsDir, "$it.kts").exists() }
        if (missing.isNotEmpty())
            return@registerTool err("Agents not in store: $missing — create them with create_agent first")

        val pipelineId = "pipeline-${System.currentTimeMillis()}"
        File(agentsDir, "$pipelineId.kts").writeText(buildPipelineScript(pipelineId, stages))

        val qDir = File(jobsDir, queue).also { it.mkdirs() }
        File(qDir, "$pipelineId.json").writeText(
            """{"id":"$pipelineId","fileName":"$pipelineId","functionName":"run","scriptPath":"agents/$pipelineId.kts","sourceType":"script","submittedAt":"${mcpTs()}"}"""
        )
        mapOf("ok" to true, "pipelineId" to pipelineId, "stages" to stages, "queue" to queue)
    }

    // 8 ─ cancel_job
    mcp.registerTool(
        "cancel_job",
        "Cancel a pending or in-flight job by moving it to the failed state",
        obj("type" to "object",
            "properties" to mapOf("jobId" to strP("Job ID to cancel")),
            "required" to listOf("jobId"))
    ) { args ->
        val id = args["jobId"]?.toString() ?: return@registerTool err("jobId is required")
        cancelJob(id)
    }

    // 9 ─ swarm_run
    mcp.registerTool(
        "swarm_run",
        "Run a sequence of LLM-powered agents using Koupper's SwarmCoordinator. " +
        "Each agent has a role and task; results pass to the next agent as context.",
        obj("type" to "object",
            "properties" to mapOf(
                "agents" to mapOf(
                    "type"        to "array",
                    "description" to "Ordered agent configs: [{name, role, goal, task}, ...]",
                    "items"       to mapOf(
                        "type"       to "object",
                        "properties" to mapOf(
                            "name" to strP("Agent name"),
                            "role" to strP("Agent identity / specialty"),
                            "goal" to strP("What the agent is trying to achieve"),
                            "task" to strP("Specific prompt / instruction for the agent")
                        )
                    )
                ),
                "queue" to strP("Queue for the swarm coordinator job (default: 'swarm')")
            ),
            "required" to listOf("agents"))
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val agentList = (args["agents"] as? List<*>)
            ?.mapNotNull { it as? Map<String, Any?> }
            ?: return@registerTool err("agents must be a list of {name, role, goal, task} objects")

        if (agentList.isEmpty()) return@registerTool err("agents list cannot be empty")

        val missing = agentList.mapIndexedNotNull { i, a ->
            if (a["name"] == null || a["task"] == null) "agents[$i] missing name or task" else null
        }
        if (missing.isNotEmpty()) return@registerTool err(missing.joinToString("; "))

        val swarmId = "swarm-${System.currentTimeMillis()}"
        File(agentsDir, "$swarmId.kts").writeText(buildSwarmScript(swarmId, agentList))

        val queue = args["queue"]?.toString() ?: "swarm"
        val qDir  = File(jobsDir, queue).also { it.mkdirs() }
        File(qDir, "$swarmId.json").writeText(
            """{"id":"$swarmId","fileName":"$swarmId","functionName":"run","scriptPath":"agents/$swarmId.kts","sourceType":"script","submittedAt":"${mcpTs()}"}"""
        )
        mapOf("ok" to true, "swarmId" to swarmId, "agents" to agentList.size, "queue" to queue)
    }
}
