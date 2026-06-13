package com.koupper.octopus.annotations

import com.koupper.container.app
import com.koupper.logging.LogSpec
import com.koupper.orchestrator.KouTask
import com.koupper.providers.files.JSONFileHandler
import com.koupper.shared.octopus.readTextOrNull
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TimerSetup {
    private val jsonHandler = app.getInstance(JSONFileHandler::class)
    private val scheduler = Executors.newScheduledThreadPool(2)
    private var replaySpec: LogSpec? = null
    private val registeredTimers = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Public registry: scriptKey → effective timer entry. Read by CortexWebUiAgent for the Calendar. */
    val timerRegistry = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()

    fun attachLogSpec(spec: LogSpec) { replaySpec = spec }

    fun run(jlc: JobsListenerCall, injector: (String) -> Any? = { null }): Any {
        val scriptKey = jlc.scriptPath ?: jlc.functionName
        if (registeredTimers.containsKey(scriptKey)) {
            return "⏭️ Already registered timer: $scriptKey — skipping re-registration"
        }
        registeredTimers[scriptKey] = true

        val params = jlc.annotationParams
        val intervalRaw = params["interval"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val atRaw       = params["at"]?.toString()?.trim()?.takeIf { it.isNotBlank() }

        val agentFileName = jlc.scriptPath?.let { File(it).name } ?: jlc.functionName

        val workerTask = KouTask(
            id = java.util.UUID.randomUUID().toString(),
            fileName = agentFileName,
            functionName = jlc.functionName,
            params = emptyMap(),
            signature = emptyList<String>() to "Any",
            scriptPath = jlc.scriptPath,
            origin = "timerSetup",
            context = jlc.scriptContext,
            sourceType = "script",
            sourceSnapshot = readTextOrNull(jlc.scriptPath),
            scheduledAt = null,
            cron = null,
            fixedRate = parseIntervalMs(intervalRaw)
        )

        return when {
            intervalRaw != null -> {
                val rateMs = parseIntervalMs(intervalRaw)
                    ?: return "⚠️ @Timer: invalid interval '$intervalRaw'"
                scheduler.scheduleAtFixedRate({
                    enqueueJob(workerTask, "timer/interval:${intervalRaw}")
                }, 0, rateMs, TimeUnit.MILLISECONDS)
                timerRegistry[scriptKey] = mapOf("id" to scriptKey, "agent" to agentFileName, "type" to "rate", "rateMs" to rateMs, "enabled" to true)
                "⏱️ Timer registered for '$agentFileName' — every ${intervalRaw}"
            }

            atRaw != null -> {
                val runAt = runCatching { ZonedDateTime.parse(atRaw) }.getOrElse {
                    return "⚠️ @Timer: invalid 'at' datetime '$atRaw'"
                }
                val diff = java.time.Duration.between(ZonedDateTime.now(), runAt).toMillis().coerceAtLeast(0)
                scheduler.schedule({
                    enqueueJob(workerTask, "timer/at:${atRaw}")
                }, diff, TimeUnit.MILLISECONDS)
                timerRegistry[scriptKey] = mapOf("id" to scriptKey, "agent" to agentFileName, "type" to "once", "runAt" to atRaw, "enabled" to true)
                "⏰ Timer registered for '$agentFileName' — runs at $runAt"
            }

            else -> "⚠️ @Timer: must provide 'interval' or 'at'"
        }
    }

    private fun parseIntervalMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { return it }
        val m = Regex("""^(\d+)\s*([smhd])$""").find(raw.trim()) ?: return null
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2]) {
            "s" -> n * 1_000L
            "m" -> n * 60_000L
            "h" -> n * 3_600_000L
            "d" -> n * 86_400_000L
            else -> null
        }
    }

    private fun enqueueJob(task: KouTask, triggeredBy: String) {
        val scriptPath = task.scriptPath ?: return
        val jobsDir = System.getenv("CORTEX_JOBS_DIR")?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".koupper/jobs")
        val queueDir = File(jobsDir, "default").also { it.mkdirs() }

        val agentName = File(scriptPath).name.removeSuffix(".kts")
        val jobId = "$agentName-timer-${System.currentTimeMillis()}"
        val submittedAt = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        File(queueDir, "$jobId.json").writeText(
            """{"id":"$jobId","fileName":"$agentName","functionName":"${task.functionName}","scriptPath":"$scriptPath","sourceType":"script","triggeredBy":"$triggeredBy","submittedAt":"$submittedAt"}"""
        )
    }
}
