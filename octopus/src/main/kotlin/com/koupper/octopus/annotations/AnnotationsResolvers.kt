package com.koupper.octopus.annotations

import com.koupper.logging.LogSpec

object LoggerAnnotationResolver : AnnotationResolver {
    override fun prepare(
        scriptPath: String?,
        baseParams: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        context: String
    ) {
        val level = (annotationParams["level"] ?: "INFO").toString()
        val dest  = (annotationParams["destination"] ?: "console").toString()

        baseParams["logSpec"] = LogSpec(
            level = level,
            destination = dest,
            mdc = mapOf("script" to (scriptPath ?: "n/a")),
            async = true
        )
    }
}

object JobsListenerAnnotationResolver : AnnotationResolver {
    override fun prepare(
        scriptPath: String?,
        baseParams: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        context: String
    ) {
        baseParams["jobsListenerParams"] = annotationParams
    }
}

val annotationResolvers: Map<String, AnnotationResolver> = mapOf(
    "Export"       to object : AnnotationResolver {
        override fun prepare(sp: String?, bp: MutableMap<String, Any>, ap: Map<String, Any>, s: String, c: String) {}
    },
    "Logger"       to LoggerAnnotationResolver,
    "JobsListener" to JobsListenerAnnotationResolver
)

