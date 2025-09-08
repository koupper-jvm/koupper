package com.koupper.octopus.annotations

interface AnnotationResolver {
    fun prepare(
        scriptPath: String?,
        baseParams: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        context: String
    )
}
