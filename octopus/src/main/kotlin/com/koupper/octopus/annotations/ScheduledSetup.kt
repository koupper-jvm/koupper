package com.koupper.octopus.annotations

import com.koupper.container.app
import com.koupper.logging.LogSpec
import com.koupper.orchestrator.*
import com.koupper.orchestrator.config.JobConfig
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.readAs
import com.koupper.shared.isSimpleType
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.ExportFunctionSignature
import com.koupper.shared.octopus.extractExportFunctionSignature
import com.koupper.shared.octopus.looksLikeObjectLiteral
import com.koupper.shared.octopus.normalizeObjectLiteralToJson
import com.koupper.shared.octopus.readTextOrNull
import java.io.File
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ScheduledSetup {
    private lateinit var jlc: JobsListenerCall
    private lateinit var scheduledParams: Map<*, *>
    private var workerConfigId: String? = null
    private val jsonHandler = app.getInstance(JSONFileHandler::class)
    private lateinit var injector: (String) -> Any?
    private val scheduler = Executors.newScheduledThreadPool(2)
    private var replaySpec: LogSpec? = null
    private val registeredScripts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private var pipelineChain: List<String> = emptyList()
    private var pipelineId: String = ""

    /** Public registry: scriptKey → effective schedule entry. Read by CortexWebUiAgent for the Calendar. */
    val scheduleRegistry = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()

    /** Returns true if this script is already registered — used by AnnotationsProcessor to skip re-registration on worker re-invocations. */
    fun isRegistered(key: String): Boolean = registeredScripts.containsKey(key)

    fun attachLogSpec(spec: LogSpec) { replaySpec = spec }

    fun run(jlc: JobsListenerCall, injector: (String) -> Any? = { null }): Any {
        val scriptKey = jlc.scriptPath ?: jlc.functionName
        if (registeredScripts.containsKey(scriptKey)) {
            return "⏭️ Already scheduled: $scriptKey — skipping re-registration"
        }
        registeredScripts[scriptKey] = true
        this.jlc = jlc
        this.injector = injector
        this.scheduledParams = jlc.annotationParams as? Map<*, *> ?: emptyMap<Any?, Any?>()
        this.workerConfigId = scheduledParams["configId"] as? String
        this.pipelineChain = (scheduledParams["chain"] as? String)
            ?.split(">")?.map { it.trim().removeSuffix(".kts") }?.filter { it.isNotBlank() } ?: emptyList()
        this.pipelineId = (scheduledParams["id"] as? String)?.takeIf { it.isNotBlank() }
            ?: File(jlc.scriptPath ?: jlc.functionName).nameWithoutExtension
        return createScheduledJob()
    }

    private fun toJsonValue(raw: String): String = when {
        raw.startsWith("{") || raw.startsWith("[") -> raw        // object / array
        raw.startsWith("\"") -> raw                              // already quoted string
        raw == "true" || raw == "false" -> raw                   // boolean
        raw.toDoubleOrNull() != null -> raw                      // number
        raw == "null" -> raw                                     // null
        else -> "\"${raw.replace("\\", "\\\\").replace("\"", "\\\"")}\""  // bare string → quote it
    }

    private fun enqueueJob(workerTask: KouTask, triggeredBy: String) {
        val scriptPath = workerTask.scriptPath ?: return
        val home = System.getProperty("user.home")
        val jobsDir = System.getenv("CORTEX_JOBS_DIR")?.let { File(it) }
            ?: File(home, ".koupper/jobs")
        // Use the annotation's configId as queue name if it looks like a simple name,
        // otherwise fall back to "default"
        val queue = workerConfigId
            ?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') }
            ?: "default"
        val queueDir = File(jobsDir, queue).also { it.mkdirs() }

        val agentName = File(scriptPath).name.removeSuffix(".kts")
        val jobId = "$agentName-scheduled-${System.currentTimeMillis()}"
        val submittedAt = java.time.LocalTime.now()
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        val inputFragment = when {
            workerTask.params.isEmpty() -> ""
            workerTask.params.size == 1 -> {
                val v = toJsonValue(workerTask.params["arg0"] ?: workerTask.params.values.first())
                ""","input":$v"""
            }
            else -> {
                val arr = workerTask.params.entries
                    .sortedBy { it.key.removePrefix("arg").toIntOrNull() ?: Int.MAX_VALUE }
                    .joinToString(",") { toJsonValue(it.value) }
                ""","input":[$arr]"""
            }
        }

        File(queueDir, "$jobId.json").writeText(
            """{"id":"$jobId","fileName":"$agentName","functionName":"${workerTask.functionName}","scriptPath":"$scriptPath","sourceType":"script","triggeredBy":"$triggeredBy","submittedAt":"$submittedAt"$inputFragment,"traceId":"${com.koupper.octopus.TraceContext.get()}"}"""
        )
    }

    private fun enqueuePipelineJob(firstAgent: String, chain: List<String>, cron: String, id: String) {
        val home = System.getProperty("user.home")
        val agentsDir = File(home, ".koupper/agents").also { it.mkdirs() }
        val jobsDir = System.getenv("CORTEX_JOBS_DIR")?.let { File(it) } ?: File(home, ".koupper/jobs")
        val queue = workerConfigId?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') } ?: "default"
        val queueDir = File(jobsDir, queue).also { it.mkdirs() }

        // The trigger script itself is NOT a stage — it declared the schedule.
        // Coordinator runs only the chain agents (which must be pure @Export scripts).
        val stages = chain
        val ts = System.currentTimeMillis()
        val coordinatorName = "$id-coordinator-$ts"
        val coordinatorFile = File(agentsDir, "$coordinatorName.kts")
        coordinatorFile.writeText(buildPipelineCoordinator(coordinatorName, stages))

        val submittedAt = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val jobId = "$coordinatorName-job"
        File(queueDir, "$jobId.json").writeText(
            """{"id":"$jobId","fileName":"$coordinatorName","functionName":"setup","scriptPath":"${coordinatorFile.absolutePath}","sourceType":"script","triggeredBy":"pipeline/cron:$cron","submittedAt":"$submittedAt","traceId":"${com.koupper.octopus.TraceContext.get()}"}"""
        )
    }

    private fun buildPipelineCoordinator(coordinatorName: String, stages: List<String>): String {
        val home = System.getProperty("user.home")
        val koupperBin = "$home/.koupper/bin/koupper"
        val agentsDir = "$home/.koupper/agents"

        // Use regular fun declarations instead of val lambdas — avoids a K2/FIR NPE
        // (FirJvmModuleAccessibilityTypeChecker crashes on null PSI source in lambdas).
        val stageFuns = stages.joinToString("\n\n") { name ->
            """fun runStage_$name() {
    println("[PIPELINE:STAGE:$name:START]")
    val t0 = System.currentTimeMillis()
    val exit = ProcessBuilder("$koupperBin", "run", "$agentsDir/$name.kts")
        .inheritIO().start().waitFor()
    val elapsed = System.currentTimeMillis() - t0
    if (exit != 0) {
        println("[PIPELINE:STAGE:$name:FAILED:${'$'}elapsed]")
        error("Stage $name failed (exit ${'$'}exit)")
    }
    println("[PIPELINE:STAGE:$name:DONE:${'$'}elapsed]")
}"""
        }

        val stageCalls = stages.joinToString("\n    ") { "runStage_$it()" }
        val stageList = stages.joinToString(",")

        return """// $coordinatorName.kts — Pipeline coordinator (auto-generated by @Pipeline)
// Stages: ${stages.joinToString(" → ")}
import com.koupper.shared.annotations.Export

$stageFuns

@Export
val setup: () -> Unit = {
    println("[PIPELINE:START:$stageList]")
    $stageCalls
    println("[PIPELINE:DONE]")
}
"""
    }

    private fun asMap(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<Any?, Any?>()

    private fun toLongOrNull(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun toBoolean(value: Any?, default: Boolean = false): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> default
    }

    private fun toStringOrNull(value: Any?): String? {
        val text = value?.toString()?.trim()
        return text?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun targetMatches(config: Map<String, Any?>): Boolean {
        val target = asMap(config["target"])
        if (target.isEmpty()) return true

        val export = toStringOrNull(target["export"])
        val script = toStringOrNull(target["script"])

        if (!export.isNullOrBlank() && export != jlc.functionName) return false

        if (!script.isNullOrBlank()) {
            val currentScriptPath = jlc.scriptPath ?: return false
            val current = java.nio.file.Paths.get(currentScriptPath).normalize().toString().replace('\\', '/')
            val expected = java.nio.file.Paths.get(script).normalize().toString().replace('\\', '/')
            if (!(current == expected || current.endsWith("/$expected") || current.endsWith(expected))) return false
        }

        return true
    }

    private fun resolveConfigOverride(rawConfigs: List<Map<String, Any?>>): Map<String, Any?>? {
        if (rawConfigs.isEmpty()) return null

        val selected = if (!workerConfigId.isNullOrBlank()) {
            rawConfigs.firstOrNull { it["id"]?.toString() == workerConfigId }
        } else {
            rawConfigs.firstOrNull { targetMatches(it) }
        }

        return selected
    }

    private data class SchedulePlan(
        val enabled: Boolean,
        val debug: Boolean,
        val cron: String?,
        val rate: Long,
        val delay: Long,
        val at: String?
    )

    private fun buildSchedulePlan(configOverride: Map<String, Any?>?): SchedulePlan {
        val nestedSchedule = asMap(configOverride?.get("schedule"))
        val nestedLogging = asMap(configOverride?.get("logging"))

        val enabled = toBoolean(configOverride?.get("enabled"), default = true)

        val debug =
            toBoolean(nestedLogging["debug"], default = false) ||
                toBoolean(configOverride?.get("debug"), default = false) ||
                scheduledParams["debug"].toString().equals("true", ignoreCase = true)

        val mode = toStringOrNull(nestedSchedule["mode"])?.lowercase()

        val cron = when {
            mode == "cron" -> toStringOrNull(nestedSchedule["cron"])
            else -> toStringOrNull(nestedSchedule["cron"]) ?: toStringOrNull(configOverride?.get("cron"))
                ?: (scheduledParams["cron"] as? String)?.takeIf { it.isNotBlank() }
        }

        val rate = when {
            mode == "rate" -> toLongOrNull(nestedSchedule["rateMs"]) ?: toLongOrNull(nestedSchedule["rate"]) ?: 0L
            else -> toLongOrNull(nestedSchedule["rateMs"]) ?: toLongOrNull(nestedSchedule["rate"]) ?: toLongOrNull(configOverride?.get("rate"))
                ?: toLongOrNull(scheduledParams["rate"]) ?: 0L
        }

        val delay = when {
            mode == "delay" -> toLongOrNull(nestedSchedule["delayMs"]) ?: toLongOrNull(nestedSchedule["delay"]) ?: 0L
            else -> toLongOrNull(nestedSchedule["delayMs"]) ?: toLongOrNull(nestedSchedule["delay"]) ?: toLongOrNull(configOverride?.get("delay"))
                ?: toLongOrNull(scheduledParams["delay"]) ?: 0L
        }

        val at = when {
            mode == "at" -> toStringOrNull(nestedSchedule["at"])
            else -> toStringOrNull(nestedSchedule["at"]) ?: toStringOrNull(configOverride?.get("at"))
                ?: (scheduledParams["at"] as? String)?.takeIf { it.isNotBlank() }
        }

        return SchedulePlan(
            enabled = enabled,
            debug = debug,
            cron = cron,
            rate = rate,
            delay = delay,
            at = at
        )
    }

    private fun createScheduledJob(): String {
        val rawConfigs = JobConfig.loadRawConfigs(this.jlc.scriptContext, null, "schedules.json")
        val configOverride = resolveConfigOverride(rawConfigs)

        if (!workerConfigId.isNullOrBlank() && !rawConfigs.isEmpty() && configOverride == null) {
            error("⚠️ No config found for id=$workerConfigId")
        }

        val schedulePlan = buildSchedulePlan(configOverride)

        if (!schedulePlan.enabled) {
            return "⏸️ Scheduled job '${jlc.functionName}' disabled by configuration"
        }

        val functionSignature = extractExportFunctionSignature(this.jlc.code)
        val functionNameAndSignature = mapOf(
            this.jlc.functionName to (
                    functionSignature ?: ExportFunctionSignature(
                        packageName = null,
                        imports = emptyMap(),
                        parameterTypes = emptyList(),
                        returnType = "kotlin.Any"
                    )
                    )
        )
        val functionArgTypeNames = functionNameAndSignature[this.jlc.functionName]?.parameterTypes ?: emptyList()

        val orderedParams = this.jlc.paramsJson.entries.sortedBy { it.key.removePrefix("arg").toIntOrNull() ?: Int.MAX_VALUE }
        val finalParams = ArrayList<Any?>(functionArgTypeNames.size)
        var posIdx = 0

        outer@ for (argName in functionArgTypeNames) {
            val isInjectable = this.injector(argName)

            if (isInjectable != null) {
                finalParams += isInjectable
                continue@outer
            }

            val key = "arg$posIdx"
            val raw = orderedParams.firstOrNull { it.key == key }?.value
            val isNullable = argName.trim().endsWith("?")

            if (raw == null) {
                if (isNullable) {
                    finalParams += null
                    posIdx += 1
                    continue
                } else error("Falta '$key' en params para tipo '$argName'")
            }

            val token = raw.trim()

            val unwrapped = if (token.length >= 2 && token[0] == '"' &&
                (token.getOrNull(1) == '{' || token.getOrNull(1) == '[')
            ) {
                jsonHandler.readAs<String>(token)
            } else token

            val value: Any? =
                if (argName.isSimpleType()) {
                    when (argName.normalizeType()) {
                        "String" -> {
                            if (unwrapped != null && unwrapped.length >= 2 &&
                                unwrapped.first() == '"' && unwrapped.last() == '"'
                            ) {
                                jsonHandler.readAs<String>(unwrapped)
                            } else {
                                unwrapped
                            }
                        }
                        "Int"     -> jsonHandler.readAs<Int>(unwrapped)
                        "Long"    -> jsonHandler.readAs<Long>(unwrapped)
                        "Double"  -> jsonHandler.readAs<Double>(unwrapped)
                        "Boolean" -> jsonHandler.readAs<Boolean>(unwrapped)
                        "Float"   -> jsonHandler.readAs<Float>(unwrapped)
                        "Short"   -> jsonHandler.readAs<Short>(unwrapped)
                        "Byte"    -> jsonHandler.readAs<Byte>(unwrapped)
                        "Char"    -> jsonHandler.readAs<String>(unwrapped)?.single()
                        else      -> jsonHandler.readAs<Any>(unwrapped)
                    }
                } else if (looksLikeObjectLiteral(unwrapped!!)) {
                    normalizeObjectLiteralToJson(unwrapped)
                } else null

            if (value != null) {
                finalParams += value
            }

            posIdx += 1
        }

        val jsonAny = app.getInstance(JSONFileHandler::class)
        val paramValues = finalParams.mapIndexed { i, arg ->
            val serialized = if (arg is String) arg else jsonAny.toJson(arg)
            "arg$i" to serialized
        }.toMap()

        val cron = schedulePlan.cron
        val rate = schedulePlan.rate

        val workerTask = KouTask(
            id = java.util.UUID.randomUUID().toString(),
            fileName = File(this.jlc.scriptPath!!).name,
            functionName = this.jlc.functionName,
            params = paramValues,
            signature = functionArgTypeNames to (functionNameAndSignature[this.jlc.functionName]?.returnType ?: "Any"),
            scriptPath = this.jlc.scriptPath,
            origin = "scheduledSetup",
            context = this.jlc.scriptContext,
            sourceType = "script",
            sourceSnapshot = readTextOrNull(this.jlc.scriptPath),
            scheduledAt = null,
            cron = cron,
            fixedRate = rate
        )

        val delay = schedulePlan.delay
        val at = schedulePlan.at

        val scriptKey = jlc.scriptPath ?: jlc.functionName
        val agentFileName = jlc.scriptPath?.let { File(it).name } ?: jlc.functionName

        when {
            // 🕒 CRON MODE
            !cron.isNullOrBlank() -> {
                val parser = com.cronutils.parser.CronParser(
                    com.cronutils.model.definition.CronDefinitionBuilder.instanceDefinitionFor(
                        com.cronutils.model.CronType.UNIX
                    )
                )
                val cronExpr = parser.parse(cron)
                cronExpr.validate()
                val executionTime = com.cronutils.model.time.ExecutionTime.forCron(cronExpr)

                val capturedChain = pipelineChain.toList()
                val capturedPipelineId = pipelineId
                val capturedAgentName = agentFileName.removeSuffix(".kts")

                scheduler.submit {
                    while (true) {
                        val now = ZonedDateTime.now()
                        val next = executionTime.nextExecution(now).orElse(null) ?: break
                        val delay = java.time.Duration.between(now, next).toMillis()
                        Thread.sleep(delay)
                        if (capturedChain.isNotEmpty()) {
                            enqueuePipelineJob(capturedAgentName, capturedChain, cron, capturedPipelineId)
                        } else {
                            enqueueJob(workerTask, "scheduled/cron:$cron")
                        }
                    }
                }

                if (pipelineChain.isNotEmpty()) {
                    val allStages = listOf(capturedAgentName) + capturedChain
                    scheduleRegistry[scriptKey] = mapOf(
                        "id" to capturedPipelineId,
                        "agent" to allStages.joinToString(" → "),
                        "type" to "cron", "cron" to cron,
                        "enabled" to schedulePlan.enabled,
                        "pipeline" to true
                    )
                    return "🔗 Pipeline '${allStages.joinToString(" → ")}' scheduled with CRON: $cron"
                } else {
                    scheduleRegistry[scriptKey] = mapOf("id" to scriptKey, "agent" to agentFileName, "type" to "cron", "cron" to cron, "enabled" to schedulePlan.enabled)
                    return "🕒 Scheduled job '${jlc.functionName}' running with CRON: $cron"
                }
            }

            rate > 0 -> {
                val capturedChain2 = pipelineChain.toList()
                val capturedId2 = pipelineId
                val capturedAgent2 = agentFileName.removeSuffix(".kts")
                scheduler.scheduleAtFixedRate({
                    if (capturedChain2.isNotEmpty()) enqueuePipelineJob(capturedAgent2, capturedChain2, "rate:${rate}ms", capturedId2)
                    else enqueueJob(workerTask, "scheduled/rate:${rate}ms")
                }, 0, rate, TimeUnit.MILLISECONDS)
                val rateEntry = if (pipelineChain.isNotEmpty()) {
                    val allStages2 = listOf(capturedAgent2) + capturedChain2
                    mapOf("id" to capturedId2, "agent" to allStages2.joinToString(" → "), "type" to "rate", "rateMs" to rate, "enabled" to schedulePlan.enabled, "pipeline" to true)
                } else {
                    mapOf("id" to scriptKey, "agent" to agentFileName, "type" to "rate", "rateMs" to rate, "enabled" to schedulePlan.enabled)
                }
                scheduleRegistry[scriptKey] = rateEntry
                return if (pipelineChain.isNotEmpty()) "🔗 Pipeline repeating every ${rate}ms" else "🔁 Scheduled job '${jlc.functionName}' repeating every ${rate}ms"
            }

            delay > 0 -> {
                val capturedChain3 = pipelineChain.toList()
                val capturedId3 = pipelineId
                val capturedAgent3 = agentFileName.removeSuffix(".kts")
                scheduler.schedule({
                    if (capturedChain3.isNotEmpty()) enqueuePipelineJob(capturedAgent3, capturedChain3, "delay:${delay}ms", capturedId3)
                    else enqueueJob(workerTask, "scheduled/delay:${delay}ms")
                }, delay, TimeUnit.MILLISECONDS)
                val delayEntry = if (pipelineChain.isNotEmpty()) {
                    val allStages3 = listOf(capturedAgent3) + capturedChain3
                    mapOf("id" to capturedId3, "agent" to allStages3.joinToString(" → "), "type" to "once", "enabled" to schedulePlan.enabled, "pipeline" to true)
                } else {
                    mapOf("id" to scriptKey, "agent" to agentFileName, "type" to "rate", "rateMs" to delay, "enabled" to schedulePlan.enabled)
                }
                scheduleRegistry[scriptKey] = delayEntry
                return if (pipelineChain.isNotEmpty()) "🔗 Pipeline fires in ${delay}ms" else "⏳ Scheduled job '${jlc.functionName}' delayed for ${delay}ms"
            }

            !at.isNullOrBlank() -> {
                val runAt = ZonedDateTime.parse(at)
                val now = ZonedDateTime.now()
                val diff = java.time.Duration.between(now, runAt).toMillis().coerceAtLeast(0)
                scheduler.schedule({
                    enqueueJob(workerTask, "scheduled/at:$at")
                }, diff, TimeUnit.MILLISECONDS)
                scheduleRegistry[scriptKey] = mapOf("id" to scriptKey, "agent" to agentFileName, "type" to "once", "runAt" to at, "enabled" to schedulePlan.enabled)
                return "⏰ Scheduled job '${jlc.functionName}' scheduled for $runAt"
            }

            else -> {
                enqueueJob(workerTask, "scheduled/immediate")
                scheduleRegistry[scriptKey] = mapOf("id" to scriptKey, "agent" to agentFileName, "type" to "once", "enabled" to schedulePlan.enabled)
                return "🚀 Scheduled job enqueued immediately"
            }
        }
    }
}
