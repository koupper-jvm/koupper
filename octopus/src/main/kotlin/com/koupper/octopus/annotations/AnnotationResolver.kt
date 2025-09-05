package com.koupper.octopus.annotations

import javax.script.ScriptEngine

interface AnnotationResolver {
    fun <T> resolve(
        scriptPath: String?,
        params: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        engine: ScriptEngine,
        context: String,
        resultCallback: (T) -> Unit
    )
}
