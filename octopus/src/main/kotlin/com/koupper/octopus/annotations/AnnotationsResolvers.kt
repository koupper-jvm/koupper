package com.koupper.octopus.annotations

import com.koupper.octopus.exportResolvers
import com.koupper.octopus.jobsListenerResolvers
import com.koupper.octopus.utils.captureOutputWithConfig
import com.koupper.shared.octopus.extractExportFunctionSignature
import javax.script.ScriptEngine

object ExportResolver : AnnotationResolver {
    override fun <T> resolve(
        scriptPath: String?,
        params: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        engine: ScriptEngine,
        context: String,
        resultCallback: (T) -> Unit
    ) {
        val functionParams = extractExportFunctionSignature(sentence)?.first
            ?.map { it.replace("\\s+".toRegex(), "") }
            ?: emptyList()

        val resolver = exportResolvers[functionParams]
            ?: throw IllegalArgumentException("Unsupported function signature: $functionParams")

        val loggerLevel = (params["level"] ?: "INFO") as String
        val destination = (params["destination"] ?: "console") as String

        val output = captureOutputWithConfig(loggerLevel, destination) {
            resolver(context, sentence, engine, params)
        }

        resultCallback(output as T)
    }
}

object JobsListenerResolver : AnnotationResolver {
    override fun <T> resolve(
        scriptPath: String?,
        params: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        engine: ScriptEngine,
        context: String,
        resultCallback: (T) -> Unit
    ) {
        val functionParams = extractExportFunctionSignature(sentence)?.first
            ?.map { it.replace("\\s+".toRegex(), "") }
            ?: emptyList()

        val fullParams = params + mapOf(
            "jobsListenerParams" to annotationParams
        )

        val resolver = jobsListenerResolvers[functionParams]
            ?: throw IllegalArgumentException("Unsupported function signature: $functionParams")

        val loggerLevel = (annotationParams["level"] ?: "INFO") as String
        val destination = (annotationParams["destination"] ?: "console") as String

        val output = captureOutputWithConfig(loggerLevel, destination) {
            resolver(context, scriptPath!!, sentence, engine, fullParams)
        }

        resultCallback(output as T)
    }
}

