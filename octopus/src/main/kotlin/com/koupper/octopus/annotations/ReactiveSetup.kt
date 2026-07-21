package com.koupper.octopus.annotations

import com.koupper.logging.LogSpec
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Handles reactive annotations: @OnQueueEmpty, @OnJobFailed, @OnFileCreated, @OnAgentDown.
 * Each condition runs a background polling/watching thread and enqueues the agent
 * when the condition is met, respecting the cooldown between fires.
 */
object ReactiveSetup {
    private val executor = Executors.newCachedThreadPool()
    private val registered = ConcurrentHashMap<String, Boolean>()
    private val lastFired  = ConcurrentHashMap<String, Long>()
    private var replaySpec: LogSpec? = null

    fun attachLogSpec(spec: LogSpec) { replaySpec = spec }

    // ── Entry points per annotation ────────────────────────────────────────────

    fun runOnQueueEmpty(jlc: JobsListenerCall): Any {
        val key = "queue-empty:${jlc.scriptPath ?: jlc.functionName}"
        if (registered.containsKey(key)) return "⏭️ Already watching queue-empty: $key"
        registered[key] = true

        val queue    = jlc.annotationParams["queue"]?.toString() ?: "default"
        val cooldown = parseCooldownMs(jlc.annotationParams["cooldown"]?.toString() ?: "60m")
        val agentFile = jlc.scriptPath?.let { File(it).name } ?: jlc.functionName

        TriggerRegistry.entries[key] = mapOf("id" to key, "agent" to agentFile, "type" to "trigger",
            "trigger" to "queue_empty", "queue" to queue, "cooldown" to cooldown, "enabled" to true)

        executor.submit {
            while (true) {
                Thread.sleep(30_000)
                if (!canFire(key, cooldown)) continue
                val jobsDir = resolveJobsDir(queue)
                val hasPending = jobsDir.listFiles { f -> f.extension == "json" }?.isNotEmpty() == true
                val hasProcessing = jobsDir.listFiles { f -> f.extension == "processing" }?.isNotEmpty() == true
                if (!hasPending && !hasProcessing) {
                    lastFired[key] = System.currentTimeMillis()
                    enqueueJob(jlc, "trigger/queue_empty:$queue")
                }
            }
        }
        return "👁️ Watching queue '$queue' — fires when empty (cooldown: ${jlc.annotationParams["cooldown"] ?: "60m"})"
    }

    fun runOnJobFailed(jlc: JobsListenerCall): Any {
        val key = "job-failed:${jlc.scriptPath ?: jlc.functionName}"
        if (registered.containsKey(key)) return "⏭️ Already watching job-failed: $key"
        registered[key] = true

        val queue    = jlc.annotationParams["queue"]?.toString() ?: "default"
        val cooldown = parseCooldownMs(jlc.annotationParams["cooldown"]?.toString() ?: "30m")
        val agentFile = jlc.scriptPath?.let { File(it).name } ?: jlc.functionName

        TriggerRegistry.entries[key] = mapOf("id" to key, "agent" to agentFile, "type" to "trigger",
            "trigger" to "job_failed", "queue" to queue, "cooldown" to cooldown, "enabled" to true)

        executor.submit {
            while (true) {
                Thread.sleep(15_000)
                if (!canFire(key, cooldown)) continue
                val failedDir = File(resolveJobsDir(queue), "failed")
                if (failedDir.exists() && failedDir.listFiles()?.isNotEmpty() == true) {
                    lastFired[key] = System.currentTimeMillis()
                    enqueueJob(jlc, "trigger/job_failed:$queue")
                }
            }
        }
        return "👁️ Watching queue '$queue' for failures (cooldown: ${jlc.annotationParams["cooldown"] ?: "30m"})"
    }

    fun runOnFileCreated(jlc: JobsListenerCall): Any {
        val key = "file-created:${jlc.scriptPath ?: jlc.functionName}"
        if (registered.containsKey(key)) return "⏭️ Already watching file-created: $key"
        registered[key] = true

        val rawPath  = jlc.annotationParams["path"]?.toString() ?: return "⚠️ @OnFileCreated: 'path' is required"
        val cooldown = parseCooldownMs(jlc.annotationParams["cooldown"]?.toString() ?: "0")
        val watchPath = File(rawPath.replace("~", System.getProperty("user.home"))).also { it.mkdirs() }
        val agentFile = jlc.scriptPath?.let { File(it).name } ?: jlc.functionName

        TriggerRegistry.entries[key] = mapOf("id" to key, "agent" to agentFile, "type" to "trigger",
            "trigger" to "file_created", "path" to rawPath, "cooldown" to cooldown, "enabled" to true)

        executor.submit {
            val ws = FileSystems.getDefault().newWatchService()
            watchPath.toPath().register(ws, ENTRY_CREATE)
            while (true) {
                val wk = ws.poll(5, TimeUnit.SECONDS) ?: continue
                wk.pollEvents().forEach { _ ->
                    if (canFire(key, cooldown)) {
                        lastFired[key] = System.currentTimeMillis()
                        enqueueJob(jlc, "trigger/file_created:$rawPath")
                    }
                }
                wk.reset()
            }
        }
        return "👁️ Watching '$rawPath' for new files"
    }

    fun runOnAgentDown(jlc: JobsListenerCall): Any {
        val key = "agent-down:${jlc.scriptPath ?: jlc.functionName}"
        if (registered.containsKey(key)) return "⏭️ Already watching agent-down: $key"
        registered[key] = true

        val agent    = jlc.annotationParams["agent"]?.toString() ?: return "⚠️ @OnAgentDown: 'agent' is required"
        val cooldown = parseCooldownMs(jlc.annotationParams["cooldown"]?.toString() ?: "5m")
        val agentFile = jlc.scriptPath?.let { File(it).name } ?: jlc.functionName

        TriggerRegistry.entries[key] = mapOf("id" to key, "agent" to agentFile, "type" to "trigger",
            "trigger" to "agent_down", "watchedAgent" to agent, "cooldown" to cooldown, "enabled" to true)

        executor.submit {
            while (true) {
                Thread.sleep(30_000)
                if (!canFire(key, cooldown)) continue
                val pidFile = File(System.getProperty("user.home"), ".koupper/run/${agent.removeSuffix(".kts").lowercase()}.pid")
                val isDown = if (pidFile.exists()) {
                    val pid = pidFile.readText().trim().toLongOrNull()
                    pid == null || !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                } else true
                if (isDown) {
                    lastFired[key] = System.currentTimeMillis()
                    enqueueJob(jlc, "trigger/agent_down:$agent")
                }
            }
        }
        return "👁️ Watching agent '$agent' — fires when down (cooldown: ${jlc.annotationParams["cooldown"] ?: "5m"})"
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private fun canFire(key: String, cooldownMs: Long): Boolean {
        if (cooldownMs <= 0) return true
        val last = lastFired[key] ?: return true
        return System.currentTimeMillis() - last >= cooldownMs
    }

    private fun enqueueJob(jlc: JobsListenerCall, triggeredBy: String) {
        val scriptPath = jlc.scriptPath ?: return
        val agentName  = File(scriptPath).name.removeSuffix(".kts")
        val jobsDir    = System.getenv("CORTEX_JOBS_DIR")?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".koupper/jobs")
        val queueDir   = File(jobsDir, "default").also { it.mkdirs() }
        val jobId      = "$agentName-reactive-${System.currentTimeMillis()}"
        val submittedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        File(queueDir, "$jobId.json").writeText(
            """{"id":"$jobId","fileName":"$agentName","functionName":"${jlc.functionName}","scriptPath":"$scriptPath","sourceType":"script","triggeredBy":"$triggeredBy","submittedAt":"$submittedAt"}"""
        )
    }

    private fun resolveJobsDir(queue: String): File {
        val base = System.getenv("CORTEX_JOBS_DIR")?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".koupper/jobs")
        return if (queue == "default") base else File(base, queue)
    }

    private fun parseCooldownMs(raw: String): Long {
        if (raw.isBlank() || raw == "0") return 0L
        raw.toLongOrNull()?.let { return it }
        val m = Regex("""^(\d+)\s*([smhd])$""").find(raw.trim()) ?: return 0L
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2]) {
            "s" -> n * 1_000L
            "m" -> n * 60_000L
            "h" -> n * 3_600_000L
            "d" -> n * 86_400_000L
            else -> 0L
        }
    }
}
