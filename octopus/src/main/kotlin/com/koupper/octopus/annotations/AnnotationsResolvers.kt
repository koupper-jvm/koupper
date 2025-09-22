package com.koupper.octopus.annotations

import com.koupper.logging.LogSpec

object LoggerAnnotationResolver : AnnotationResolver {
    override fun prepareParams(
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
    override fun prepareParams(
        scriptPath: String?,
        baseParams: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        context: String
    ) {
        baseParams["jobsListenerParams"] = annotationParams
    }
}

object TimerAnnotationResolver : AnnotationResolver {
    override fun prepareParams(
        scriptPath: String?,
        baseParams: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        context: String
    ) {
        val interval     = (annotationParams["interval"] ?: "").toString()
        val initialDelay = (annotationParams["initialDelay"] ?: "").toString()
        val repeat       = (annotationParams["repeat"] ?: true).toString()

        baseParams["timerParams"] = mapOf(
            "interval"     to interval,
            "initialDelay" to initialDelay,
            "repeat"       to repeat,
            "scriptPath"   to (scriptPath ?: "n/a"),
            "sentence"     to sentence
        )
    }
}

object ScheduleAnnotationResolver : AnnotationResolver {
    override fun prepareParams(
        scriptPath: String?,
        baseParams: MutableMap<String, Any>,
        annotationParams: Map<String, Any>,
        sentence: String,
        context: String
    ) {
        val at         = (annotationParams["at"] ?: "").toString()
        val zone       = (annotationParams["zone"] ?: "").toString()
        val skipIfPast = (annotationParams["skipIfPast"] ?: true).toString()

        baseParams["scheduleParams"] = mapOf(
            "at"         to at,
            "zone"       to zone,
            "skipIfPast" to skipIfPast,
            "scriptPath" to (scriptPath ?: "n/a"),
            "sentence"   to sentence
        )
    }
}

val annotationResolvers: Map<String, AnnotationResolver> = mapOf(
    "Export"       to object : AnnotationResolver {
        override fun prepareParams(
            sp: String?,
            bp: MutableMap<String, Any>,
            ap: Map<String, Any>,
            s: String,
            c: String
        ) { /* no-op */ }
    },
    "Logger"       to LoggerAnnotationResolver,
    "JobsListener" to JobsListenerAnnotationResolver,
    "Timer"        to TimerAnnotationResolver,
    "Schedule"     to ScheduleAnnotationResolver
)
