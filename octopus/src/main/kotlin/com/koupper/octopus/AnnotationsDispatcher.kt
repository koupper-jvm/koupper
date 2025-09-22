package com.koupper.octopus

import com.koupper.logging.KLogger
import com.koupper.logging.LogSpec
import com.koupper.logging.captureLogs

object AnnotationDispatcher {

    fun dispatch(
        params: Map<String, Any>
    ) {
        when (params["annotationName"]) {
            "Logger" -> {
                val logger: Map<String, String> =
                    params["annotationParams"] as? Map<String, String> ?: emptyMap()

                val logSpec = LogSpec(
                    level = logger["level"] ?: "DEBUG",
                    destination = logger["destination"] ?: "console",
                    mdc = mapOf(
                        "script"  to (params["scriptPath"]  as? String ?: "unknown"),
                        "export"  to (params["functionName"] as? String ?: "unknown"),
                        "context" to (params["context"]     as? String ?: "n/a")
                    ),
                    async = logger["async"]?.equals("true", ignoreCase = true) ?: true
                )
            }
            "JobsListener" -> {

            }
            "Export" -> {

            }
        }
    }
}