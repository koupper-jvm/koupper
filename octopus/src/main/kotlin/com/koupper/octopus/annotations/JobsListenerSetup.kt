package com.koupper.octopus.annotations

import com.koupper.container.app
import com.koupper.logging.LogSpec
import com.koupper.octopus.process.JobEvent
import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.octopus.process.RoutesRegistration
import com.koupper.orchestrator.*
import com.koupper.orchestrator.config.JobConfig
import com.koupper.orchestrator.config.JobConfiguration
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.readTo
import com.koupper.providers.files.toJsonAny
import com.koupper.providers.files.toType
import com.koupper.shared.isSimpleType
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.extractExportFunctionSignature
import com.koupper.shared.octopus.readTextOrNull
import com.koupper.shared.runtime.ScriptingHostBackend
import java.io.File
import java.nio.file.Paths

data class JobsListenerCall(
    val scriptContext: String,
    val scriptPath: String?,
    val functionName: String,
    val code: String,
    val argTypes: List<String>? = null,
    val paramsJson: Map<String, String> = emptyMap(),
    val symbol: Any? = null,
    val annotationParams: Map<String, Any?>
)

object JobsListenerSetup {
    private lateinit var jlc: JobsListenerCall
    private lateinit var jobListenerParams: Map<*, *>
    private var workerConfigId: String? = null
    private lateinit var workerDriver: String
    @Suppress("UNCHECKED_CAST")
    private val jsonHandler = app.getInstance(JSONFileHandler::class) as JSONFileHandler<Any?>
    private lateinit var backend: ScriptingHostBackend
    private lateinit var injector: (String) -> Any?

    private fun looksLikeObjectLiteral(s: String): Boolean = s.trim().let { it.startsWith("{") && it.endsWith("}") }

    private fun normalizeObjectLiteralToJson(src: String): String {
        val s = src.trim()
        require(s.startsWith("{") && s.endsWith("}")) { "Composed input debe verse como {k=v,...}" }
        val body = s.substring(1, s.length - 1).trim()
        if (body.isBlank()) return "{}"
        return buildString {
            append('{')
            val parts = body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            parts.forEachIndexed { i, kv ->
                val (k, v) = kv.split('=', limit = 2).map { it.trim() }
                if (i > 0) append(',')
                append('"').append(k.replace("\"", "\\\"")).append('"').append(':')
                val isJsonish = v.startsWith("{") || v.startsWith("[") || v.startsWith("\"")
                if (isJsonish) append(v) else append('"').append(v.replace("\"", "\\\"")).append('"')
            }
            append('}')
        }
    }

    fun run(jlc: JobsListenerCall, injector: (String) -> Any? = { null }): Any {
        this.jlc = jlc

        this.injector = injector

        this.backend = ScriptingHostBackend()

        this.backend.eval(jlc.code)

        this.jobListenerParams = jlc.annotationParams as? Map<*, *> ?: emptyMap<Any?, Any?>()

        val creationWorkerJobResult = this.createWorkerListener()

        return creationWorkerJobResult
    }

    private fun createWorkerListener(): String {
        this.workerConfigId = jobListenerParams["configId"] as? String

        val configs = JobConfig.loadOrFail(this.jlc.scriptContext, this.workerConfigId).configurations

        if (configs.isNullOrEmpty()) {
            throw IllegalStateException("❌ No job configurations with id ${this.workerConfigId} found in context: ${this.jlc.scriptContext}")
        }

        if (configs.size > 1 && this.workerConfigId.isNullOrEmpty()) {
            throw IllegalArgumentException(
                "⚠️ Multiple job configurations detected. " +
                        "You must specify a configId to dispatch this task correctly."
            )
        }

        if (configs.size > 1 && !this.workerConfigId.isNullOrEmpty()) {
            val duplicates = configs.groupBy { it.id }.filter { it.value.size > 1 }.keys
            if (duplicates.isNotEmpty()) {
                throw IllegalStateException(
                    "❌ Duplicate configuration IDs found: ${duplicates.joinToString(", ")}"
                )
            }
        }

        val finalConfig = configs.first()

        val jobInfo = JobMetricsCollector.collect(this.jlc.scriptContext, finalConfig)

        if (jobInfo.pending > 0) {
            return "A JobListener already exists for this configuration driver[${finalConfig.driver}], queue[${finalConfig.queue}]."
        }

        val finalScriptPath = Paths.get(this.jlc.scriptContext, this.jlc.scriptPath ?: "")
            .normalize().toAbsolutePath().toString()

        val functionSignature = extractExportFunctionSignature(this.jlc.code)

        val functionNameAndSignature = if (this.jlc.functionName.isNotEmpty() && functionSignature != null) {
            mapOf(this.jlc.functionName to functionSignature)
        } else emptyMap()

        val functionArgTypeNames: List<String> = functionNameAndSignature[this.jlc.functionName]?.first ?: emptyList()

        fun argIndex(k: String) = k.removePrefix("arg").toIntOrNull() ?: Int.MAX_VALUE

        val orderedParams = this.jlc.paramsJson.entries.sortedBy { argIndex(it.key) }
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

        val paramValues: Map<String, String> =
            finalParams.mapIndexed { i, arg -> "arg$i" to jsonAny.toJsonAny(arg) }.toMap()

        val workerFileName = File(finalScriptPath).name

        val functionReturnTypeName: String = functionNameAndSignature[this.jlc.functionName]?.second ?: "kotlin.Any"

        val workerTask = KouTask(
            id = java.util.UUID.randomUUID().toString(),
            fileName = workerFileName,
            functionName = this.jlc.functionName,
            params = paramValues,
            signature = functionArgTypeNames to functionReturnTypeName,
            scriptPath = finalScriptPath,
            packageName = null,
            origin = "listenerSetup",
            context = this.jlc.scriptContext,
            sourceType = "script",
            contextVersion = "unknown",
            sourceSnapshot = readTextOrNull(finalScriptPath),
            artifactUri = null,
            artifactSha256 = null
        )

        JobDispatcher.dispatch(workerTask, finalConfig)

        val listenByJobsResult = this.listenByJobsToThrow(workerTask, finalConfig)

        return "Worker listener created. \n$listenByJobsResult"
    }

    private var replaySpec: LogSpec? = null

    fun attachLogSpec(spec: LogSpec) { replaySpec = spec }

    private fun Any?.asBool(default: Boolean = false): Boolean = when (this) {
        null       -> default
        is Boolean -> this
        is String  -> this.equals("true", true) || this == "1" || this.equals("yes", true) || this.equals("on", true)
        is Number  -> this.toInt() != 0
        else       -> default
    }

    private fun listenByJobsToThrow(workerTask : KouTask, config: JobConfiguration): String {
        val sleepTime = (this.jobListenerParams["time"] as? Long) ?: 5000L
        val key       = "${this.jlc.scriptContext}::${config.id}"

        val debug = this.jobListenerParams["debug"].asBool(false)

        if (debug) enableDebugMode()

        ListenersRegistry.start(
            key = key,
            sleepTime = sleepTime,
            runOnce = { onJob ->
                JobRunner.runPendingJobs(workerTask.context, jobId = null, configId = null) { jobs -> onJob(jobs) }
            },
            onJob = { results ->
                results.forEach { result ->
                    when (result) {
                        is JobResult.Ok -> {
                            val t = result.task

                            val event = JobEvent(
                                jobId          = t.id,
                                function       = t.functionName,
                                context        = t.context,
                                contextVersion = t.contextVersion,
                                origin         = t.origin,
                                packageName    = t.packageName,
                                scriptPath     = t.scriptPath,
                                finishedAt     = System.currentTimeMillis()
                            )

                            fun jsonStringToKotlin(className: String, valueJson: String): Any? {
                                val clazz = try {
                                    Class.forName(className)
                                } catch (e: ClassNotFoundException) {
                                    throw IllegalArgumentException("Class not found for name: $className", e)
                                }

                                return try {
                                    jsonHandler.read(valueJson).toType(clazz)
                                } catch (e: Exception) {
                                    throw IllegalStateException("Failed to parse JSON into class $className: ${e.message}", e)
                                }
                            }

                            val workerFunctionArgs: List<String> = workerTask.signature.first

                            val argsParams: MutableMap<String, Any?> = linkedMapOf()

                            fun argIndex(k: String) = k.removePrefix("arg").toIntOrNull() ?: Int.MAX_VALUE

                            val orderedParams = this.jlc.paramsJson.entries.sortedBy { argIndex(it.key) }
                            var userIdx = 0

                            loop@ for (argName in workerFunctionArgs) {
                                val inj = this.injector(argName)

                                if (inj != null) {
                                    argsParams[argName] = inj
                                    userIdx += 1
                                    continue@loop
                                }

                                val argKey = "arg$userIdx"
                                val raw = orderedParams.firstOrNull { it.key == argKey }?.value
                                val isNullable = argName.trim().endsWith("?")

                                if (raw == null) {
                                    if (isNullable) {
                                        argsParams[argName] = null
                                        userIdx += 1
                                        continue@loop
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
                                        val normalizedObject = normalizeObjectLiteralToJson(unwrapped)

                                        jsonStringToKotlin(argName, normalizedObject)
                                    } else null

                                if (value != null) {
                                    argsParams[argName] = value
                                }

                                userIdx += 1
                            }

                            val mergedParams: Map<String, Any?> = argsParams

                            JobReplayer.replayJobsListenerScript(
                                context = workerTask.context,
                                config,
                                newParams = mergedParams,
                                injector = { typeName ->
                                    when (typeName.normalizeType()) {
                                        "JobRunner"          -> JobRunner
                                        "JobLister"          -> JobLister
                                        "JobBuilder"         -> JobBuilder
                                        "JobDisplayer"       -> JobDisplayer
                                        "RoutesRegistration" -> RoutesRegistration(this.jlc.scriptContext)
                                        "ModuleAnalyzer"     -> ModuleAnalyzer(this.jlc.scriptContext)
                                        "ModuleProcessor"    -> ModuleProcessor(this.jlc.scriptContext)
                                        "JobEvent"           -> event
                                        else -> null
                                    }
                                },
                                logSpec = replaySpec,
                                symbol = this.backend.getSymbol(jlc.functionName),
                            ) { updatedWorkerJob ->
                                updatedWorkerJob.dispatchToQueue(config.id)
                            }
                        }
                    }
                }
            }
        )

        return "JobListener initialized"
    }
}
