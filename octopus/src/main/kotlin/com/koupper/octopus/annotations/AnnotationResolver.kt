package com.koupper.octopus.annotations

interface AnnotationResolver {
    fun prepareParams(
        scriptPath: String?,
        baseParams: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        context: String
    )
}
