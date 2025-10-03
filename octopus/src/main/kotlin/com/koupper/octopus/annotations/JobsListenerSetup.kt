package com.koupper.octopus.annotations

import com.koupper.container.app
import com.koupper.logging.LogSpec
import com.koupper.octopus.ParsedParams
import com.koupper.octopus.process.JobEvent
import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.octopus.process.RoutesRegistration
import com.koupper.orchestrator.*
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.readAs
import com.koupper.providers.files.toJsonAny
import com.koupper.shared.isSimpleType
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.extractExportFunctionSignature
import com.koupper.shared.runtime.ScriptingHostBackend
import java.io.File
import java.nio.file.Paths

data class JobsListenerCall(
    val functionName: String,
    val code: String,
    val annotationParams: Map<*, *>,
    val params: ParsedParams?,
    val scriptPath: String?,
    val scriptContext: String
)

object JobsListenerSetup {
    private lateinit var jlc: JobsListenerCall
    private lateinit var jobListenerParams: Map<*, *>
    private lateinit var workerQueue: String
    private lateinit var workerDriver: String
    @Suppress("UNCHECKED_CAST")
    private val jsonHandler = app.getInstance(JSONFileHandler::class) as JSONFileHandler<Any?>
    private lateinit var backend: ScriptingHostBackend

    private fun tryInject(typeName: String, env: Map<String, Any?>): Any? = when (typeName) {
        "JobEvent" -> JobEvent()
        "JobRunner" -> JobRunner
        "JobLister" -> JobLister
        "JobBuilder" -> JobBuilder
        "JobDisplayer" -> JobDisplayer
        "RoutesRegistration" -> RoutesRegistration(env["context"] as String)
        "ModuleAnalyzer" -> ModuleAnalyzer(env["context"] as String, *(env["flags"] as? Array<String> ?: emptyArray()))
        "ModuleProcessor" -> ModuleProcessor(
            env["context"] as String, *(env["flags"] as? Array<String> ?: emptyArray())
        )

        else -> null
    }

    private fun parseArg(expected: String, raw: Any?): Any? = when (expected) {
        "String" -> when (raw) {
            null -> null; is String -> raw; else -> raw.toString()
        }

        "Int" -> when (raw) {
            is Number -> raw.toInt(); is String -> raw.toInt(); else -> error("Arg no parseable a Int")
        }

        "Long" -> when (raw) {
            is Number -> raw.toLong(); is String -> raw.toLong(); else -> error("Arg no parseable a Long")
        }

        "Double" -> when (raw) {
            is Number -> raw.toDouble(); is String -> raw.toDouble(); else -> error("Arg no parseable a Double")
        }

        "Boolean" -> when (raw) {
            is Boolean -> raw; is String -> raw.equals(
                "true", true
            ) || raw == "1"; else -> error("Arg no parseable a Boolean")
        }

        else -> raw
    }

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

    private fun getJobDriverFromConfig(context: String): String? {
        val f = File("$context/jobs.json"); if (!f.exists()) return null
        val rx = """"driver"\s*:\s*"([\w\-]+)"""".toRegex()
        return rx.find(f.readText())?.groupValues?.get(1)
    }

    private fun getJobQueueFromConfig(context: String): String? {
        val f = File("$context/jobs.json"); if (!f.exists()) return null
        val rx = """"queue"\s*:\s*"([\w\-]+)"""".toRegex()
        return rx.find(f.readText())?.groupValues?.get(1)
    }

    fun run(jlc: JobsListenerCall): Any {
        this.jlc = jlc

        this.backend = ScriptingHostBackend()

        this.backend.eval(jlc.code)

        this.jobListenerParams = jlc.annotationParams as? Map<*, *> ?: emptyMap<Any?, Any?>()

        val creationWorkerJobResult = this.createWorkerListener()

        return creationWorkerJobResult
    }

    private fun createWorkerListener(): String {
        this.workerQueue = jobListenerParams["queue"] as? String ?: "job-callbacks"

        this.workerDriver = (jobListenerParams["driver"] as? String) ?: "file"

        val jobInfo = JobMetricsCollector.collect(workerQueue, workerDriver)

        if (jobInfo.pending > 0) {
            return "A JobListener already exists"
        }

        val finalScriptPath = Paths.get(this.jlc.scriptContext, this.jlc.scriptPath ?: "")
            .normalize().toAbsolutePath().toString()
        val flags: Set<String> = this.jlc.params?.flags ?: emptySet()
        val paramssw: Map<String, String> = this.jlc.params?.params ?: emptyMap()
        val positionals: List<String> = this.jlc.params?.positionals ?: emptyList()

        val hasComposedFlag = flags.any { it == "-ci" || it == "--ci" }
        val composedFromFlag: String? = params["ci"]
        var composedConsumed = false

        val baseEnv: Map<String, Any?> = mapOf("context" to this.jlc.scriptContext)

        val functionSignature = extractExportFunctionSignature(this.jlc.code)

        val functionNameAndSignature = if (this.jlc.functionName.isNotEmpty() && functionSignature != null) {
            mapOf(this.jlc.functionName to functionSignature)
        } else emptyMap()

        val functionArgTypeNames: List<String> = functionNameAndSignature[this.jlc.functionName]?.first ?: emptyList()
        val functionReturnTypeName: String = functionNameAndSignature[this.jlc.functionName]?.second ?: "kotlin.Any"

        val finalParams = ArrayList<Any?>(functionArgTypeNames.size)
        var posIdx = 0

        outer@ for (argName in functionArgTypeNames) {
            val isInjectable = tryInject(argName, baseEnv)

            if (isInjectable != null) {
                finalParams += isInjectable
                continue
            }

            if (argName.isSimpleType()) {
                val rawValue = positionals.getOrNull(posIdx) ?: params["arg$posIdx"]
                ?: return "Missing positional arguments for simple parameter '$argName' at position $posIdx"
                posIdx += 1
                finalParams += parseArg(argName, rawValue)
                continue
            }

            val jsonLiteral: String? = when {
                hasComposedFlag && !composedConsumed && composedFromFlag != null -> {
                    composedConsumed = true; composedFromFlag
                }
                positionals.getOrNull(posIdx)?.let { looksLikeObjectLiteral(it) } == true -> {
                    val rawObj = positionals[posIdx]; posIdx += 1; rawObj
                }
                else -> null
            }

            require(jsonLiteral != null) {
                "Missing composed input for compound type '$argName' (use -ci {..} or provide a literal {..} at this position)"
            }

            finalParams += normalizeObjectLiteralToJson(jsonLiteral)
        }

        @Suppress("UNCHECKED_CAST")
        val jsonAny = app.getInstance(JSONFileHandler::class) as JSONFileHandler<Any?>

        val paramsMap: Map<String, String> =
            finalParams.mapIndexed { i, arg -> "arg$i" to jsonAny.toJsonAny(arg) }.toMap()

        val fileName = File(finalScriptPath).name

        val workerTask = KouTask(
            id = java.util.UUID.randomUUID().toString(),
            fileName = fileName,
            functionName = this.jlc.functionName,
            params = paramsMap,
            signature = functionArgTypeNames to functionReturnTypeName,
            scriptPath = finalScriptPath,
            packageName = null,
            origin = "koupper",
            context = this.jlc.scriptContext,
            sourceType = "script",
            queue = workerQueue,
            driver = workerDriver,
            contextVersion = "unknown",
            sourceSnapshot = com.koupper.shared.octopus.readTextOrNull(finalScriptPath),
            artifactUri = null,
            artifactSha256 = null
        )

        JobDispatcher.dispatch(workerTask, queue = workerQueue, driver = workerDriver)

        val listenByJobsResult = this.listenByJobsTo(workerTask)

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

    private fun listenByJobsTo(workerTask : KouTask): String {
        val cfgQueue  = getJobQueueFromConfig(this.jlc.scriptContext) ?: "default"
        val cfgDriver = getJobDriverFromConfig(this.jlc.scriptContext) ?: "file"
        val sleepTime = (this.jobListenerParams["time"] as? Long) ?: 5000L
        val key       = "${this.jlc.scriptContext}::$cfgQueue"

        val debug = this.jobListenerParams["debug"].asBool(false)

        if (debug) enableDebugMode()

        ListenersRegistry.start(
            key = key,
            sleepTime = sleepTime,
            runOnce = { onJob ->
                JobRunner.runPendingJobs(workerTask.context, queue = cfgQueue, driver = cfgDriver) { job -> onJob(job) }
            },
            onJob = { job ->
                val event = JobEvent(
                    jobId          = job.id,
                    queue          = job.queue,
                    driver         = cfgDriver,
                    function       = job.functionName,
                    context        = job.context,
                    contextVersion = job.contextVersion,
                    origin         = job.origin,
                    packageName    = job.packageName,
                    scriptPath     = job.scriptPath,
                    finishedAt     = System.currentTimeMillis()
                )

                fun jsonStringToKotlin(valueJson: String): Any? {
                    val t = valueJson.trim()
                    return when {
                        t.startsWith("{") -> jsonHandler.readAs<Map<String, Any?>>(t)
                        t.startsWith("[") -> jsonHandler.readAs<List<Any?>>(t)
                        t.startsWith("\"")-> jsonHandler.readAs<String>(t)
                        t.equals("true", true) || t.equals("false", true) -> jsonHandler.readAs<Boolean>(t)
                        t.matches(Regex("-?\\d+"))        -> jsonHandler.readAs<Long>(t)
                        t.matches(Regex("-?\\d+\\.\\d+")) -> jsonHandler.readAs<Double>(t)
                        t.equals("null", true)            -> null
                        else -> t
                    }
                }

                val workerFunctionArgs: List<String> = workerTask.signature?.first ?: emptyList()

                val jobEventIndex = workerFunctionArgs.indexOfFirst { it.normalizeType() == "JobEvent" }

                val argsParams: MutableMap<String, Any?> = linkedMapOf()

                workerTask.params.entries
                    .filter { (k, _) -> k.startsWith("arg") }
                    .sortedBy { (k, _) -> k.removePrefix("arg").toIntOrNull() ?: Int.MAX_VALUE }
                    .forEach { (k, vJson) ->
                        val idx = k.removePrefix("arg").toIntOrNull() ?: return@forEach
                        argsParams[k] = if (idx == jobEventIndex) {
                            event
                        } else {
                            jsonStringToKotlin(vJson)
                        }
                    }

                workerFunctionArgs.forEachIndexed { index, arg ->
                    val k = "arg$index"
                    val v = argsParams[k] ?: return@forEachIndexed
                    when (arg.normalizeType()) {
                        "Int"     -> if (v is Number) argsParams[k] = v.toInt()
                        "Long"    -> if (v is Number) argsParams[k] = v.toLong()
                        "Double"  -> if (v is Number) argsParams[k] = v.toDouble()
                        "Float"   -> if (v is Number) argsParams[k] = v.toFloat()
                        "Short"   -> if (v is Number) argsParams[k] = v.toShort()
                        "Byte"    -> if (v is Number) argsParams[k] = v.toByte()
                    }
                }

                val mergedParams: Map<String, Any?> = argsParams

                JobReplayer.replayJobsListenerScript(
                    context = workerTask.context,
                    queue = workerQueue,
                    driver = workerDriver,
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
                    updatedWorkerJob.dispatchToQueue(queue = workerQueue, driver = workerDriver)
                }
            }
        )

        return "JobListener initialized"
    }
}
