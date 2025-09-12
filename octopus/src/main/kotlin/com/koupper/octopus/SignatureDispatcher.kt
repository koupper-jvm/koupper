package com.koupper.octopus

import com.koupper.logging.KLogger
import com.koupper.logging.LogSpec
import com.koupper.logging.captureLogs
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

        val baseResolver: (String, String, ScriptEngine, Map<String, Any>, KLogger) -> Any

        if (isJobsListener) {
            val jl: (String, String, String, ScriptEngine, Map<String, Any>, KLogger) -> Any =
                jobsListenerResolvers[functionParams]
                    ?: error("Unsupported JobListener function signature: $functionParams")

            baseResolver = { ctx, sent, eng, p, logger ->
                jl(ctx, scriptPath ?: "", sent, eng, p, logger)
            }
        } else {
            baseResolver =
                exportResolvers[functionParams]
                    ?: error("Unsupported Export function signature: $functionParams")
        }

        val logSpec: LogSpec = (params["logSpec"] as? LogSpec)
            ?: LogSpec(
                level = "DEBUG",
                destination = "console",
                mdc = mapOf(
                    "script"  to (scriptPath ?: "n/a"),
                    "export"  to (params["exportName"] as? String ?: "unknown"),
                    "context" to (params["context"] as? String ?: "n/a")
                ),
                async = true
            )

        val mergedParams = params.toMutableMap().apply {
            this["mdc"] = logSpec.mdc
            this["logSpec"] = logSpec
        }

        val (v, _) = captureLogs("Scripts.Dispatcher", logSpec) { log ->
            baseResolver(context, sentence, engine, mergedParams, log)
        }

        @Suppress("UNCHECKED_CAST")
        result(v as T)
    }
}
