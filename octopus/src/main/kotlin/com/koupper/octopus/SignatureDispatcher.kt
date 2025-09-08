package com.koupper.octopus

import com.koupper.octopus.logging.LogSpec
import com.koupper.octopus.logging.captureLogs
import com.koupper.shared.octopus.extractExportFunctionSignature
import javax.script.ScriptEngine

object SignatureDispatcher {
    fun <T> dispatch(
        context: String,
        scriptPath: String?,
        sentence: String,
        engine: ScriptEngine,
        params: Map<String, Any>,
        result: (T) -> Unit
    ) {
        val functionParams: List<String> =
            extractExportFunctionSignature(sentence)
                ?.first
                ?.map { it.replace("\\s+".toRegex(), "") }
                ?: emptyList()

        val isJobsListener = ((params["annotations"] as? Map<*, *>)?.containsKey("JobsListener") == true)

        val baseResolver: (String, String, ScriptEngine, Map<String, Any>) -> Any

        if (isJobsListener) {
            val jl: (String, String, String, ScriptEngine, Map<String, Any>) -> Any =
                jobsListenerResolvers[functionParams]
                    ?: error("Unsupported JobListener function signature: $functionParams")

            baseResolver = { ctx, sent, eng, p ->
                jl(ctx, scriptPath ?: "", sent, eng, p)
            }
        } else {
            baseResolver =
                exportResolvers[functionParams]
                    ?: error("Unsupported Export function signature: $functionParams")
        }

        val logSpec: LogSpec? = (params["annotations"] as? Map<*, *>)?.get("Logger")
            ?.let { ann ->
                val loggerAnn = ann as Map<*, *>
                val level = (loggerAnn["level"] as? String)?.uppercase() ?: "DEBUG"
                val destination = (loggerAnn["destination"] as? String) ?: "file"
                val async: Boolean = when (val a = loggerAnn["async"]) {
                    is Boolean -> a
                    is String  -> a.equals("true", ignoreCase = true)
                    else       -> true
                }

                LogSpec(
                    level = level,
                    destination = destination,
                    mdc = mapOf(
                        "script"  to (scriptPath ?: "n/a"),
                        "export"  to (params["exportName"] as? String ?: "unknown"),
                        "context" to (params["context"] as? String ?: "n/a")
                    ),
                    async = async
                )
            }

        val value: Any =
            if (logSpec != null) {
                val (v, _) = captureLogs("Octopus.Dispatcher", logSpec) { log ->
                    val newParams = params + mapOf("dispatcherLogger" to log)
                    baseResolver(context, sentence, engine, newParams)
                }
                v
            } else {
                baseResolver(context, sentence, engine, params)
            }

        @Suppress("UNCHECKED_CAST")
        result(value as T)
    }
}
