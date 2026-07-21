package com.koupper.monitor

import com.koupper.providers.mcp.LocalMCPServerProvider
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class CortexToolsTest : StringSpec({

    fun setup(): Triple<LocalMCPServerProvider, File, File> {
        val jobsDir   = Files.createTempDirectory("cortex-jobs-").toFile().also {
            File(it, "default").mkdirs()
            File(it, "logs").mkdirs()
            File(it, "commands/wizard").mkdirs()
        }
        val agentsDir = Files.createTempDirectory("cortex-agents-").toFile()
        val mcp = LocalMCPServerProvider()
        registerCortexTools(mcp, jobsDir, agentsDir)
        return Triple(mcp, jobsDir, agentsDir)
    }

    // ── list_agents ───────────────────────────────────────────────────────────

    "list_agents should return empty list when no agents exist" {
        val (mcp, _, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("list_agents", emptyMap<String, Any?>()) as Map<String, Any?>
            result["count"] shouldBe 0
        } finally { agentsDir.deleteRecursively() }
    }

    "list_agents should return all .kts files with name and description" {
        val (mcp, _, agentsDir) = setup()
        try {
            File(agentsDir, "AlphaAgent.kts").writeText("// Description: Does alpha things\ncode")
            File(agentsDir, "BetaAgent.kts").writeText("// Role: Beta role\ncode")
            File(agentsDir, "gamma.txt").writeText("not an agent")

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("list_agents", emptyMap<String, Any?>()) as Map<String, Any?>
            result["count"] shouldBe 2

            @Suppress("UNCHECKED_CAST")
            val agents = result["agents"] as List<Map<String, Any?>>
            agents.map { it["name"] }.toSet() shouldBe setOf("AlphaAgent", "BetaAgent")
        } finally { agentsDir.deleteRecursively() }
    }

    // ── create_agent ──────────────────────────────────────────────────────────

    "create_agent should write .kts file to agents directory" {
        val (mcp, _, agentsDir) = setup()
        val content = "import com.koupper.shared.annotations.Export\n@" +
            "Export val setup: () -> Unit = { println(\"hi\") }"
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("create_agent", mapOf(
                "name"    to "HelloAgent",
                "content" to content
            )) as Map<String, Any?>

            result["ok"] shouldBe true
            File(agentsDir, "HelloAgent.kts").exists() shouldBe true
            File(agentsDir, "HelloAgent.kts").readText() shouldBe content
        } finally { agentsDir.deleteRecursively() }
    }

    "create_agent should strip special chars from name" {
        val (mcp, _, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("create_agent", mapOf(
                "name"    to "My Agent 2024!",
                "content" to "code"
            )) as Map<String, Any?>

            result["ok"] shouldBe true
            File(agentsDir, "MyAgent2024.kts").exists() shouldBe true
        } finally { agentsDir.deleteRecursively() }
    }

    "create_agent should return error when name is empty after sanitization" {
        val (mcp, _, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("create_agent", mapOf(
                "name"    to "!@#\$%",
                "content" to "code"
            )) as Map<String, Any?>

            result.containsKey("error") shouldBe true
        } finally { agentsDir.deleteRecursively() }
    }

    "create_agent should return error when name is missing" {
        val (mcp, _, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("create_agent", mapOf("content" to "code")) as Map<String, Any?>
            result.containsKey("error") shouldBe true
        } finally { agentsDir.deleteRecursively() }
    }

    // ── run_agent ─────────────────────────────────────────────────────────────

    "run_agent should reject agent not in store" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("run_agent", mapOf("name" to "GhostAgent")) as Map<String, Any?>

            result.containsKey("error") shouldBe true
            (result["error"] as String) shouldContain "not found"
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    "run_agent should submit job to default queue when agent exists" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            File(agentsDir, "RealAgent.kts").writeText("code")

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("run_agent", mapOf("name" to "RealAgent")) as Map<String, Any?>

            result["ok"]    shouldBe true
            result["agent"] shouldBe "RealAgent"
            result["queue"] shouldBe "default"

            val jobId = result["jobId"] as String
            File(jobsDir, "default/$jobId.json").exists() shouldBe true
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    "run_agent should submit to custom queue when specified" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            File(agentsDir, "WorkerAgent.kts").writeText("code")
            File(jobsDir, "priority").mkdirs()

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("run_agent", mapOf(
                "name"  to "WorkerAgent",
                "queue" to "priority"
            )) as Map<String, Any?>

            result["queue"] shouldBe "priority"
            val jobId = result["jobId"] as String
            File(jobsDir, "priority/$jobId.json").exists() shouldBe true
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    // ── inspect_swarm ─────────────────────────────────────────────────────────

    "inspect_swarm should report pending jobs and agent count" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            File(agentsDir, "A.kts").writeText("code")
            File(agentsDir, "B.kts").writeText("code")
            File(jobsDir, "default/job1.json").writeText("{}")
            File(jobsDir, "default/job2.json").writeText("{}")

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("inspect_swarm", emptyMap<String, Any?>()) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val totals = result["totals"] as Map<String, Int>
            totals["pending"] shouldBe 2

            @Suppress("UNCHECKED_CAST")
            val store = result["agentStore"] as Map<String, Any?>
            store["count"] shouldBe 2
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    "inspect_swarm should report processing and failed counts" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            File(jobsDir, "default/job1.json.processing").writeText("{}")
            File(jobsDir, "default/.failed").mkdirs()
            File(jobsDir, "default/.failed/job2.json").writeText("{}")

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("inspect_swarm", emptyMap<String, Any?>()) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val totals = result["totals"] as Map<String, Int>
            totals["processing"] shouldBe 1
            totals["failed"]     shouldBe 1
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    // ── job_status ────────────────────────────────────────────────────────────

    "job_status should return PENDING when job file exists" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            File(jobsDir, "default/my-job-123.json").writeText("{}")

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("job_status", mapOf("jobId" to "my-job-123")) as Map<String, Any?>
            result["status"] shouldBe "PENDING"
            result["queue"]  shouldBe "default"
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    "job_status should return FAILED when job is in failed dir" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            File(jobsDir, "default/.failed").mkdirs()
            File(jobsDir, "default/.failed/my-job-456.json").writeText("{}")

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("job_status", mapOf("jobId" to "my-job-456")) as Map<String, Any?>
            result["status"] shouldBe "FAILED"
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    "job_status should return NOT_FOUND for unknown job" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("job_status", mapOf("jobId" to "ghost-job")) as Map<String, Any?>
            result["status"] shouldBe "NOT_FOUND"
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    // ── cancel_job ────────────────────────────────────────────────────────────

    "cancel_job should move pending job to failed directory" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            val pending = File(jobsDir, "default/cancel-me.json").also { it.writeText("{}") }

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("cancel_job", mapOf("jobId" to "cancel-me")) as Map<String, Any?>
            result["ok"]  shouldBe true
            result["was"] shouldBe "PENDING"
            pending.exists() shouldBe false
            File(jobsDir, "default/.failed/cancel-me.json").exists() shouldBe true
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    // ── fetch_url ─────────────────────────────────────────────────────────────

    "fetch_url should return error for non-http URL" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("fetch_url", mapOf("url" to "ftp://example.com")) as Map<String, Any?>
            result.containsKey("error") shouldBe true
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    "fetch_url should return error when url arg is missing" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("fetch_url", emptyMap<String, Any?>()) as Map<String, Any?>
            result.containsKey("error") shouldBe true
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    // ── pipeline_run ──────────────────────────────────────────────────────────

    "pipeline_run should reject unknown stage agents" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("pipeline_run", mapOf(
                "stages" to listOf("GhostStage1", "GhostStage2")
            )) as Map<String, Any?>
            result.containsKey("error") shouldBe true
            (result["error"] as String) shouldContain "not in store"
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    "pipeline_run should create coordinator job when all stages exist" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            File(agentsDir, "Stage1.kts").writeText("code")
            File(agentsDir, "Stage2.kts").writeText("code")
            File(jobsDir, "pipeline").mkdirs()

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("pipeline_run", mapOf(
                "stages" to listOf("Stage1", "Stage2"),
                "queue"  to "pipeline"
            )) as Map<String, Any?>

            result["ok"]     shouldBe true
            result["stages"] shouldBe listOf("Stage1", "Stage2")
            val pipelineId = result["pipelineId"] as String
            File(agentsDir, "$pipelineId.kts").exists() shouldBe true
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    // ── swarm_run ─────────────────────────────────────────────────────────────

    "swarm_run should return error when agents list is empty" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("swarm_run", mapOf("agents" to emptyList<Any>())) as Map<String, Any?>
            result.containsKey("error") shouldBe true
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }

    "swarm_run should create coordinator script and submit to queue" {
        val (mcp, jobsDir, agentsDir) = setup()
        try {
            File(jobsDir, "swarm").mkdirs()

            @Suppress("UNCHECKED_CAST")
            val result = mcp.callTool("swarm_run", mapOf(
                "agents" to listOf(
                    mapOf("name" to "Researcher", "role" to "Analyst", "goal" to "Analyze", "task" to "Do analysis"),
                    mapOf("name" to "Writer",     "role" to "Writer",  "goal" to "Write",   "task" to "Write summary")
                )
            )) as Map<String, Any?>

            result["ok"]     shouldBe true
            result["agents"] shouldBe 2
            val swarmId = result["swarmId"] as String
            File(agentsDir, "$swarmId.kts").exists() shouldBe true
            val script = File(agentsDir, "$swarmId.kts").readText()
            script shouldContain "Researcher"
            script shouldContain "Writer"
        } finally { agentsDir.deleteRecursively(); jobsDir.deleteRecursively() }
    }
})
