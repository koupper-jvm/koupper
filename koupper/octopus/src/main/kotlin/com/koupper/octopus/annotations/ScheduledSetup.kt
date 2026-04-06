package com.koupper.octopus.annotations

import com.koupper.container.app
import com.koupper.logging.LogSpec
import com.koupper.logging.captureLogs
import com.koupper.logging.toStreamRoutingConfig
import com.koupper.logging.withScriptLogger
import com.koupper.orchestrator.*
import com.koupper.orchestrator.config.JobConfig
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.readTo
import com.koupper.providers.files.toJsonAny
import com.koupper.shared.isSimpleType
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.ExportFunctionSignature
import com.koupper.shared.octopus.extractExportFunctionSignature
import com.koupper.shared.octopus.looksLikeObjectLiteral
import com.koupper.shared.octopus.normalizeObjectLiteralToJson
import com.koupper.shared.octopus.readTextOrNull
import com.koupper.shared.runtime.ScriptingHostBackend
import java.io.File
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ScheduledSetup {
    private lateinit var jlc: JobsListenerCall
    private lateinit var scheduledParams: Map<*, *>
    private var workerConfigId: String? = null
    @Suppress("UNCHECKED_CAST")
    private val jsonHandler = app.getInstance(JSONFileHandler::class) as JSONFileHandler<Any?>
    private lateinit var backend: ScriptingHostBackend
    private lateinit var injector: (String) -> Any?
    private val scheduler = Executors.newScheduledThreadPool(2)
    private var replaySpec: LogSpec? = null

    fun attachLogSpec(spec: LogSpec) { replaySpec = spec }

    fun run(jlc: JobsListenerCall, injector: (String) -> Any? = { null }): Any {
        this.jlc = jlc
        this.injector = injector
        this.backend = ScriptingHostBackend()
        this.backend.eval(jlc.code)
        this.scheduledParams = jlc.annotationParams as? Map<*, *> ?: emptyMap<Any?, Any?>()
        this.workerConfigId = scheduledParams["configId"] as? String
        return createScheduledJob()
    }

    private fun createScheduledJob(): String {
        val configs = JobConfig.loadOrFail(this.jlc.scriptContext, this.workerConfigId, "schedules.json").configurations
        require(configs!!.isNotEmpty()) { "❌ No schedule configurations found in context ${this.jlc.scriptContext}" }

        val finalConfig = if (workerConfigId != null) {
            configs.firstOrNull { it.id == workerConfigId }
                ?: error("⚠️ No config found for id=$workerConfigId")
        } else configs.first()

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
                jsonHandler.readTo<String>(token)
            } else token

            val value: Any? =
                if (argName.isSimpleType()) {
                    when (argName.normalizeType()) {
                        "String" -> {
                            if (unwrapped != null && unwrapped.length >= 2 &&
                                unwrapped.first() == '"' && unwrapped.last() == '"'
                            ) {
                                jsonHandler.readTo<String>(unwrapped)
                            } else {
                                unwrapped
                            }
                        }
                        "Int"     -> jsonHandler.readTo<Int>(unwrapped)
                        "Long"    -> jsonHandler.readTo<Long>(unwrapped)
                        "Double"  -> jsonHandler.readTo<Double>(unwrapped)
                        "Boolean" -> jsonHandler.readTo<Boolean>(unwrapped)
                        "Float"   -> jsonHandler.readTo<Float>(unwrapped)
                        "Short"   -> jsonHandler.readTo<Short>(unwrapped)
                        "Byte"    -> jsonHandler.readTo<Byte>(unwrapped)
                        "Char"    -> jsonHandler.readTo<String>(unwrapped)?.single()
                        else      -> jsonHandler.readTo<Any>(unwrapped)
                    }
                } else if (looksLikeObjectLiteral(unwrapped!!)) {
                    normalizeObjectLiteralToJson(unwrapped)
                } else null

            if (value != null) {
                finalParams += value
            }

            posIdx += 1
        }

        @Suppress("UNCHECKED_CAST")
        val jsonAny = app.getInstance(JSONFileHandler::class) as JSONFileHandler<Any?>
        val paramValues = finalParams.mapIndexed { i, arg ->
            val serialized = if (arg is String) arg else jsonAny.toJsonAny(arg)
            "arg$i" to serialized
        }.toMap()

        val cron = (scheduledParams["cron"] as? String)?.takeIf { it.isNotBlank() }
        val rate = when (val raw = scheduledParams["rate"]) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }
        val debug = scheduledParams["debug"].toString().equals("true", ignoreCase = true)

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

        val delay = when (val raw = scheduledParams["delay"]) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }
        val at = (scheduledParams["at"] as? String)?.takeIf { it.isNotBlank() }

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

                scheduler.submit {
                    while (true) {
                        val now = ZonedDateTime.now()
                        val next = executionTime.nextExecution(now).orElse(null) ?: break
                        val delay = java.time.Duration.between(now, next).toMillis()
                        Thread.sleep(delay)
                        captureLogs<Any?>("Scheduled.Cron", replaySpec!!) { logger ->
                            withScriptLogger(logger, replaySpec?.mdc!!, replaySpec?.toStreamRoutingConfig()) {
                                val result = ScriptRunner.runScript(workerTask, backend.getSymbol(workerTask.functionName))
                                if (debug) logger.info { "🟢 [CRON] Result: $result" }
                            }
                        }
                    }
                }
                return "🕒 Scheduled job '${jlc.functionName}' running with CRON: $cron"
            }

            rate > 0 -> {
                scheduler.scheduleAtFixedRate({
                    captureLogs<Any?>("Scheduled.Rate", replaySpec!!) { logger ->
                        withScriptLogger(logger, replaySpec?.mdc!!, replaySpec?.toStreamRoutingConfig()) {
                            val result = ScriptRunner.runScript(workerTask, backend.getSymbol(workerTask.functionName))
                            if (debug) logger.info { "🟢 [RATE] Result: $result" }
                        }
                    }
                }, 0, rate, TimeUnit.MILLISECONDS)
                return "🔁 Scheduled job '${jlc.functionName}' repeating every ${rate}ms"
            }

            delay > 0 -> {
                scheduler.schedule({
                    captureLogs<Any?>("Scheduled.Delay", replaySpec!!) { logger ->
                        withScriptLogger(logger, replaySpec?.mdc!!, replaySpec?.toStreamRoutingConfig()) {
                            val result = ScriptRunner.runScript(workerTask, backend.getSymbol(workerTask.functionName))
                            if (debug) logger.info { "🟢 [DELAY] Result: $result" }
                        }
                    }
                }, delay, TimeUnit.MILLISECONDS)
                return "⏳ Scheduled job '${jlc.functionName}' delayed for ${delay}ms"
            }

            !at.isNullOrBlank() -> {
                val runAt = ZonedDateTime.parse(at)
                val now = ZonedDateTime.now()
                val diff = java.time.Duration.between(now, runAt).toMillis().coerceAtLeast(0)
                scheduler.schedule({
                    captureLogs<Any?>("Scheduled.At", replaySpec!!) { logger ->
                        withScriptLogger(logger, replaySpec?.mdc!!, replaySpec?.toStreamRoutingConfig()) {
                            val result = ScriptRunner.runScript(workerTask, backend.getSymbol(workerTask.functionName))
                            if (debug) logger.info { "🟢 [AT] Result: $result" }
                        }
                    }
                }, diff, TimeUnit.MILLISECONDS)
                return "⏰ Scheduled job '${jlc.functionName}' scheduled for $runAt"
            }

            else -> {
                captureLogs<Any?>("Scheduled.Immediate", replaySpec!!) { logger ->
                    withScriptLogger(logger, replaySpec?.mdc!!, replaySpec?.toStreamRoutingConfig()) {
                        val result = ScriptRunner.runScript(workerTask, backend.getSymbol(workerTask.functionName))
                        if (debug) logger.info { "🟢 [IMMEDIATE] Result: $result" }
                    }
                }
                return "🚀 Scheduled job executed immediately"
            }
        }
    }
}
