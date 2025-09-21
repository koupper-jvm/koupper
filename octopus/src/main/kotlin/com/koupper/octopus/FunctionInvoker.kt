package com.koupper.octopus

import com.koupper.container.app
import com.koupper.octopus.process.JobEvent
import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.octopus.process.RoutesRegistration
import com.koupper.orchestrator.*
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.readAs
import com.koupper.providers.files.toJsonAny
import com.koupper.shared.octopus.extractExportFunctionSignature
import java.io.File
import java.nio.file.Paths

class FunctionInvoker(var annotations: Map<Map<String, Map<String, Any?>>, Boolean>) {

    private fun tryInject(typeName: String, env: Map<String, Any?>): Any? = when (typeName) {
        "JobEvent"           -> JobEvent()
        "JobRunner"          -> JobRunner
        "JobLister"          -> JobLister
        "JobBuilder"         -> JobBuilder
        "JobDisplayer"       -> JobDisplayer
        "RoutesRegistration" -> RoutesRegistration(env["context"] as String)
        "ModuleAnalyzer"     -> ModuleAnalyzer(env["context"] as String, *(env["flags"] as? Array<String> ?: emptyArray()))
        "ModuleProcessor"    -> ModuleProcessor(env["context"] as String, *(env["flags"] as? Array<String> ?: emptyArray()))
        else                 -> null
    }

    private fun parseArg(expected: String, raw: Any?): Any? = when (expected) {
        "String"  -> when (raw) { null -> null; is String -> raw; else -> raw.toString() }
        "Int"     -> when (raw) { is Number -> raw.toInt(); is String -> raw.toInt(); else -> error("Arg no parseable a Int") }
        "Long"    -> when (raw) { is Number -> raw.toLong(); is String -> raw.toLong(); else -> error("Arg no parseable a Long") }
        "Double"  -> when (raw) { is Number -> raw.toDouble(); is String -> raw.toDouble(); else -> error("Arg no parseable a Double") }
        "Boolean" -> when (raw) { is Boolean -> raw; is String -> raw.equals("true", true) || raw == "1"; else -> error("Arg no parseable a Boolean") }
        else      -> raw
    }

    private fun isSimpleType(t: String): Boolean =
        t in setOf(
            "String","Int","Long","Double","Boolean","Float","Short","Byte","Char",
            "kotlin.String","kotlin.Int","kotlin.Long","kotlin.Double","kotlin.Boolean",
            "kotlin.Float","kotlin.Short","kotlin.Byte","kotlin.Char"
        )

    private fun looksLikeObjectLiteral(s: String): Boolean =
        s.trim().let { it.startsWith("{") && it.endsWith("}") }

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
                append('"').append(k.replace("\"","\\\"")).append('"').append(':')
                val isJsonish = v.startsWith("{") || v.startsWith("[") || v.startsWith("\"")
                if (isJsonish) append(v) else append('"').append(v.replace("\"","\\\"")).append('"')
            }
            append('}')
        }
    }

    private fun invokeDynamic(fn: Any, args: List<Any?>): Any? {
        if (fn is kotlin.reflect.KFunction<*>) return fn.call(*args.toTypedArray())
        val m = fn.javaClass.methods.firstOrNull { it.name == "invoke" && it.parameterCount == args.size }
            ?: error("No hay 'invoke' con aridad ${args.size}")
        return m.invoke(fn, *args.toTypedArray())
    }

    /*fun <T> call(dip: DispatcherInputParams): T {
        val functionSignature = extractExportFunctionSignature(dip.sentence)

        val functionNameAndSignature =
            if (dip.functionName.isNotEmpty() && functionSignature != null) {
                mapOf(dip.functionName to functionSignature)
            } else emptyMap()

        val functionArgTypeNames: List<String> =
            functionNameAndSignature[dip.functionName]?.first ?: emptyList()

        val functionReturnTypeName: String =
            functionNameAndSignature[dip.functionName]?.second ?: "kotlin.Any"

        var lastValue: Any? = null

        outer@ for ((annMap, wasProcessed) in annotations) {
            if (!wasProcessed) continue

            if (annMap["JobsListener"] != null) {
                fun getJobDriverFromConfig(context: String): String? {
                    val f = File("$context/jobs.json"); if (!f.exists()) return null
                    val rx = """"driver"\s*:\s*"([\w\-]+)"""".toRegex()
                    return rx.find(f.readText())?.groupValues?.get(1)
                }
                fun getJobQueueFromConfig(context: String): String? {
                    val f = File("$context/jobs.json"); if (!f.exists()) return null
                    val rx = """"queue"\s*:\s*"([\w\-]+)"""".toRegex()
                    return rx.find(f.readText())?.groupValues?.get(1)
                }

                val jlParams = annMap["JobsListener"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                val queue     = jlParams["queue"]  as? String ?: "job-callbacks"
                val cfgDriver = (jlParams["driver"] as? String)
                    ?: (getJobDriverFromConfig(dip.scriptContext) ?: "default")

                val jm = JobMetricsCollector.collect(queue, cfgDriver)
                if (jm.pending > 0) {
                    lastValue = "A JobListener already exists"
                    break@outer
                }

                val finalScriptPath = Paths.get(dip.scriptContext, dip.scriptPath ?: "")
                    .normalize().toAbsolutePath().toString()

                dip.engine.eval(dip.functionName) ?: error("Símbolo no encontrado: ${dip.functionName}")

                val flags: Set<String>             = dip.params?.flags ?: emptySet()
                val kvParams: Map<String, String>  = dip.params?.params ?: emptyMap()
                val positionals: List<String>      = dip.params?.positionals ?: emptyList()

                val hasComposedFlag = flags.any { it == "-ci" || it == "--ci" }
                val composedFromFlag: String? = kvParams["ci"]

                var posIdx = 0
                var composedConsumed = false

                val baseEnv: Map<String, Any?> = mapOf(
                    "context" to dip.scriptContext
                )

                val finalParams = ArrayList<Any?>(functionArgTypeNames.size)
                for (t in functionArgTypeNames) {
                    val inj = tryInject(t, baseEnv)
                    if (inj != null) {
                        finalParams += inj
                        continue
                    }

                    if (isSimpleType(t)) {
                        val raw = positionals.getOrNull(posIdx)
                            ?: kvParams["arg$posIdx"]
                            ?: error("Faltan positionals para parámetro simple '$t' en posición $posIdx")
                        posIdx += 1
                        finalParams += parseArg(t, raw)
                        continue
                    }

                    val jsonLiteral: String? = when {
                        hasComposedFlag && !composedConsumed && composedFromFlag != null -> {
                            composedConsumed = true
                            composedFromFlag
                        }
                        positionals.getOrNull(posIdx)?.let { looksLikeObjectLiteral(it) } == true -> {
                            val rawObj = positionals[posIdx]
                            posIdx += 1
                            rawObj
                        }
                        else -> null
                    }
                    require(jsonLiteral != null) { "Falta composed input para tipo compuesto '$t' (usa -ci {..} o pasa un literal {..} en esa posición)" }

                    finalParams += normalizeObjectLiteralToJson(jsonLiteral)
                }

                @Suppress("UNCHECKED_CAST")
                val jsonAny = app.getInstance(JSONFileHandler::class) as JSONFileHandler<Any?>
                val paramsMap: Map<String, String> =
                    finalParams.mapIndexed { i, arg -> "arg$i" to jsonAny.toJsonAny(arg) }.toMap()

                val fileName = File(finalScriptPath).name
                val task = KouTask(
                    id = java.util.UUID.randomUUID().toString(),
                    fileName = fileName,
                    functionName = dip.functionName,
                    params = paramsMap,
                    signature = functionArgTypeNames to functionReturnTypeName,
                    scriptPath = finalScriptPath,
                    packageName = null,
                    origin = "koupper",
                    context = dip.scriptContext,
                    sourceType = "script",
                    queue = queue,
                    driver = cfgDriver,
                    contextVersion = "unknown",
                    sourceSnapshot = com.koupper.shared.octopus.readTextOrNull(finalScriptPath),
                    artifactUri = null,
                    artifactSha256 = null
                )

                JobDispatcher.dispatch(task, queue = queue, driver = cfgDriver)

                val cfgQueue  = getJobQueueFromConfig(dip.scriptContext) ?: "default"
                val sleepTime = (jlParams["time"] as? Long) ?: 5000L
                val key       = "${dip.scriptContext}::$cfgQueue"

                // ⚡ EJECUCIÓN DIRECTA UNA SOLA VEZ (sin listener / sin polling)
                ListenersRegistry.start(
                    key = key,
                    sleepTime = sleepTime,
                    runOnce = { onJob ->
                        JobRunner.runPendingJobs(queue = cfgQueue, driver = cfgDriver) { job -> onJob(job) }
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

                        @Suppress("UNCHECKED_CAST")
                        val jsonHandler = app.getInstance(JSONFileHandler::class) as JSONFileHandler<Any?>

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

                        fun norm(t: String) = t.removePrefix("kotlin.").substringAfterLast('.').trim()
                        val rawSig: List<String> = task.signature?.first ?: emptyList()
                        val jeIdx = rawSig.indexOfFirst { norm(it) == "JobEvent" }

                        val argsParams: MutableMap<String, Any?> = linkedMapOf()

                        paramsMap.entries
                            .filter { (k, _) -> k.startsWith("arg") }
                            .sortedBy { (k, _) -> k.removePrefix("arg").toIntOrNull() ?: Int.MAX_VALUE }
                            .forEach { (k, vJson) ->
                                val idx = k.removePrefix("arg").toIntOrNull() ?: return@forEach
                                argsParams[k] = if (idx == jeIdx) {
                                    event                 // <<--- AQUÍ SE REEMPLAZA POR EL JobEvent LLENO
                                } else {
                                    jsonStringToKotlin(vJson)
                                }
                            }

                        rawSig.forEachIndexed { i, t0 ->
                            val k = "arg$i"
                            val v = argsParams[k] ?: return@forEachIndexed
                            when (norm(t0)) {
                                "Int"     -> if (v is Number) argsParams[k] = v.toInt()
                                "Long"    -> if (v is Number) argsParams[k] = v.toLong()
                                "Double"  -> if (v is Number) argsParams[k] = v.toDouble()
                                "Float"   -> if (v is Number) argsParams[k] = v.toFloat()
                                "Short"   -> if (v is Number) argsParams[k] = v.toShort()
                                "Byte"    -> if (v is Number) argsParams[k] = v.toByte()
                                // String/Boolean/Char y complejos se dejan tal cual
                            }
                        }

                        // newParams FINAL: solo argN (p. ej. arg0, arg1, arg2=JobEvent). Nada de metadata aquí.
                        val mergedParams: Map<String, Any?> = argsParams

                        JobReplayer.replayWithParams(queue = queue, driver = cfgDriver, newParams = mergedParams) { updatedJob ->
                            val functionCode = updatedJob.sourceSnapshot

                            if (functionCode != null) {
                                try {
                                    dip.engine.eval(functionCode)

                                    val fn = dip.engine.eval(updatedJob.functionName)
                                        ?: error("Símbolo no encontrado: ${updatedJob.functionName}")

                                    val (sigParams, _) = updatedJob.signature
                                        ?: (extractExportFunctionSignature(functionCode)
                                            ?: error("No se pudo extraer firma de la función en replay"))

                                    val baseEnvReplay: Map<String, Any?> = mapOf("context" to dip.scriptContext)

                                    fun loadClassOrNull(fqcn: String): Class<*>? =
                                        try { Class.forName(fqcn, true, Thread.currentThread().contextClassLoader) }
                                        catch (_: ClassNotFoundException) { null }

                                    fun isSimple(typeName: String) = isSimpleType(typeName)

                                    val callArgs = ArrayList<Any?>(sigParams.size)

                                    var userIdx = 0

                                    for (pt in sigParams) {
                                        if (norm(pt) == "JobEvent") {
                                            val key = "arg$userIdx"
                                            val raw = updatedJob.params[key]
                                            if (raw != null) {
                                                @Suppress("UNCHECKED_CAST")
                                                val evt: JobEvent? = jsonHandler.readAs<JobEvent>(raw)
                                                callArgs += evt
                                                userIdx += 1
                                            } else {
                                                callArgs += event
                                            }
                                            continue
                                        }

                                        val inj = tryInject(pt, baseEnvReplay)
                                        if (inj != null) {
                                            callArgs += inj
                                            continue
                                        }

                                        val key = "arg$userIdx"
                                        val raw = updatedJob.params[key]
                                            ?: error("Falta '$key' en updatedJob.params durante replay")

                                        val arg: Any? = when {
                                            isSimple(pt) -> when (norm(pt)) {
                                                "String"  -> jsonHandler.readAs<String>(raw)
                                                "Int"     -> jsonHandler.readAs<Int>(raw)
                                                "Long"    -> jsonHandler.readAs<Long>(raw)
                                                "Double"  -> jsonHandler.readAs<Double>(raw)
                                                "Boolean" -> jsonHandler.readAs<Boolean>(raw)
                                                "Float"   -> jsonHandler.readAs<Float>(raw)
                                                "Short"   -> jsonHandler.readAs<Short>(raw)
                                                "Byte"    -> jsonHandler.readAs<Byte>(raw)
                                                "Char"    -> jsonHandler.readAs<Char>(raw)
                                                else      -> jsonHandler.readAs<Any?>(raw)
                                            }
                                            else -> {
                                                val target = loadClassOrNull(pt)
                                                if (target != null) {
                                                    @Suppress("UNCHECKED_CAST")
                                                    (jsonHandler as JSONFileHandler<*>).readAs(raw)
                                                } else {
                                                    raw
                                                }
                                            }
                                        }

                                        callArgs += arg
                                        userIdx += 1
                                    }

                                    val result: Any? =
                                        if (fn is kotlin.reflect.KFunction<*>) {
                                            fn.call(*callArgs.toTypedArray())
                                        } else {
                                            val lambdaClass = fn.javaClass

                                            // Busca 'invoke' aunque la clase/miembro no sea pública
                                            val invoke = lambdaClass.declaredMethods.firstOrNull {
                                                it.name == "invoke" && it.parameterCount == callArgs.size
                                            } ?: lambdaClass.methods.firstOrNull {
                                                it.name == "invoke" && it.parameterCount == callArgs.size
                                            } ?: error("No hay 'invoke' con aridad ${callArgs.size} en ${lambdaClass.name}")

                                            // Habilita acceso (Java 8: setAccessible; Java 9+: intentamos trySetAccessible por reflexión)
                                            try {
                                                invoke.isAccessible = true // -> setAccessible(true)
                                            } catch (_: Exception) {
                                                try {
                                                    val ao = Class.forName("java.lang.reflect.AccessibleObject")
                                                    val m  = ao.getMethod("trySetAccessible")
                                                    m.invoke(invoke) // solo si existe (JDK 9+). Si no, se ignora.
                                                } catch (_: Throwable) { /* ignore */ }
                                            }

                                            // Ejecuta con el classloader de la lambda
                                            val oldCl = Thread.currentThread().contextClassLoader
                                            Thread.currentThread().contextClassLoader = lambdaClass.classLoader
                                            try {
                                                invoke.invoke(fn, *callArgs.toTypedArray())
                                            } finally {
                                                Thread.currentThread().contextClassLoader = oldCl
                                            }
                                        }

                                    // Re-despacha si es tu flujo deseado
                                    updatedJob.dispatchToQueue(queue = queue, driver = cfgDriver)

                                } catch (e: Exception) {
                                    println("❌ Error replaying job [${updatedJob.id}]: ${e.message}")
                                    e.printStackTrace()
                                }
                            } else {
                                println("⚠️ Job [${job.id}] no tiene sourceSnapshot para replay.")
                            }
                        }
                    }
                )

                lastValue = "JobListener initialized"
                break@outer
            }
        }

        @Suppress("UNCHECKED_CAST")
        return lastValue as T
    }
}
*/}