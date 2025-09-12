package com.koupper.octopus

import com.koupper.logging.KLogger
import com.koupper.logging.evalExport
import com.koupper.logging.withScriptLogger
import com.koupper.octopus.process.JobEvent
import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.octopus.process.RoutesRegistration
import com.koupper.orchestrator.*
import com.koupper.shared.octopus.extractExportFunctionName
import java.io.File
import java.nio.file.Paths
import javax.script.ScriptEngine

val exportResolvers: Map<List<String>, (String, String, ScriptEngine, Map<String, Any>, KLogger) -> Any> = mapOf(

    // () -> Any
    emptyList<String>() to { _, sentence, engine, params, logger ->
        engine.eval(sentence)
        withScriptLogger(logger, params["mdc"] as? Map<String, String> ?: emptyMap()) {
            val fn = evalExport<() -> Any>(engine, sentence)
            fn()
        }
    },

    // (Map<String, Any>) -> Any
    listOf("Map<String,Any>") to { _, sentence, engine, params, logger ->
        engine.eval(sentence)
        withScriptLogger(logger, params["mdc"] as? Map<String, String> ?: emptyMap()) {
            val fn = evalExport<(Map<String, Any>) -> Any>(engine, sentence)
            fn(params)
        }
    },

    // (ModuleProcessor) -> Any
    listOf("ModuleProcessor") to { _, sentence, engine, params, logger ->
        val processor = ModuleProcessor(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        withScriptLogger(logger, params["mdc"] as? Map<String, String> ?: emptyMap()) {
            val fn = evalExport<(com.koupper.octopus.process.Process) -> Any>(engine, sentence)
            fn(processor)
        }
    },

    // (ModuleAnalyzer) -> Any
    listOf("ModuleAnalyzer") to { _, sentence, engine, params, logger ->
        val analyzer = ModuleAnalyzer(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        withScriptLogger(logger, params["mdc"] as? Map<String, String> ?: emptyMap()) {
            val fn = evalExport<(ModuleAnalyzer) -> Any>(engine, sentence)
            fn(analyzer)
        }
    },

    // (RoutesRegistration) -> Any
    listOf("RoutesRegistration") to { _, sentence, engine, params, logger ->
        val rr = RoutesRegistration(params["context"] as String)
        engine.eval(sentence)
        withScriptLogger(logger, params["mdc"] as? Map<String, String> ?: emptyMap()) {
            val fn = evalExport<(RoutesRegistration) -> Any>(engine, sentence)
            fn(rr)
        }
    },

    // (JobRunner) -> Any
    listOf("JobRunner") to { _, sentence, engine, params, logger ->
        val runner = JobRunner
        engine.eval(sentence)
        withScriptLogger(logger, params["mdc"] as? Map<String, String> ?: emptyMap()) {
            val fn = evalExport<(JobRunner) -> Any>(engine, sentence)
            fn(runner)
        }
    },

    // (JobLister) -> Any
    listOf("JobLister") to { _, sentence, engine, params, logger ->
        val runner = JobLister
        engine.eval(sentence)
        withScriptLogger(logger, params["mdc"] as? Map<String, String> ?: emptyMap()) {
            val fn = evalExport<(JobLister) -> Any>(engine, sentence)
            fn(runner)
        }
    },

    // (JobBuilder) -> Any
    listOf("JobBuilder") to { _, sentence, engine, params, logger ->
        val runner = JobBuilder
        engine.eval(sentence)
        withScriptLogger(logger, params["mdc"] as? Map<String, String> ?: emptyMap()) {
            val fn = evalExport<(JobBuilder) -> Any>(engine, sentence)
            fn(runner)
        }
    },

    // (JobDisplayer) -> Any
    listOf("JobDisplayer") to { _, sentence, engine, params, logger ->
        val runner = JobDisplayer
        engine.eval(sentence)
        withScriptLogger(logger, params["mdc"] as? Map<String, String> ?: emptyMap()) {
            val fn = evalExport<(JobDisplayer) -> Any>(engine, sentence)
            fn(runner)
        }
    }
)

val jobsListenerResolvers: Map<List<String>, (String, String, String, ScriptEngine, Map<String, Any>, KLogger) -> Any> = mapOf(
    listOf("JobEvent") to handler@{ context, scriptPath, sentence, engine, params, logger ->
        fun getJobDriverFromConfig(context: String): String? {
            val jobsJson = File("$context/jobs.json")
            if (!jobsJson.exists()) return null
            val rx = """"driver"\s*:\s*"([\w\-]+)"""".toRegex()
            return rx.find(jobsJson.readText())?.groupValues?.get(1)
        }

        fun getJobQueueFromConfig(context: String): String? {
            val jobsJson = File("$context/jobs.json")
            if (!jobsJson.exists()) return null
            val rx = """"queue"\s*:\s*"([\w\-]+)"""".toRegex()
            return rx.find(jobsJson.readText())?.groupValues?.get(1)
        }

        val jobsListenerParams = params["jobsListenerParams"] as? Map<*, *>

        val queue = jobsListenerParams?.get("queue") as? String ?: "job-callbacks"

        val cfgDriver  = (jobsListenerParams?.get("driver") as? String) ?: (getJobDriverFromConfig(context) ?: "default")

        val jm = JobMetricsCollector.collect(queue, cfgDriver)

        if (jm.pending > 0) {
            return@handler  "A JobListener already exist"
        }

        val fnName = extractExportFunctionName(sentence) ?: "anonymous"

        val finalScriptPath = Paths.get(context, scriptPath).normalize().toAbsolutePath().toString()

        val function = engine.eval(fnName) as (JobEvent) -> Any

        function.asJob(JobEvent(), functionName = fnName, scriptPath = finalScriptPath, sourceType = "script").dispatchToQueue(queue = queue)

        val cfgQueue   = getJobQueueFromConfig(context) ?: "default"
        val sleepTime  = (jobsListenerParams?.get("time") as? Long) ?: 5000L

        val key = "$context::$cfgQueue"

        ListenersRegistry.start(
            key = key,
            sleepTime = sleepTime,
            runOnce = { onJob ->
                JobRunner.runPendingJobs(queue = cfgQueue, driver = cfgDriver) { job ->
                    onJob(job)
                }
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

                val newParams = mapOf(
                    "jobId" to event.jobId,
                    "queue" to event.queue,
                    "driver" to event.driver,
                    "function" to event.function,
                    "context" to event.context,
                    "contextVersion" to event.contextVersion,
                    "origin" to event.origin,
                    "packageName" to event.packageName,
                    "scriptPath" to event.scriptPath,
                    "finishedAt" to event.finishedAt
                )

                JobReplayer.replayWithParams(
                    queue = queue,
                    driver = cfgDriver,
                    newParams = newParams
                ) { updatedJob ->

                    val functionCode = updatedJob.sourceSnapshot

                    if (functionCode != null) {
                        try {
                            engine.eval(functionCode)
                            val fn = engine.eval(updatedJob.functionName) as (JobEvent) -> Any
                            fn(event)
                            updatedJob.dispatchToQueue(queue = queue)
                        } catch (e: Exception) {
                            println("❌ Error replaying job [${updatedJob.id}]: ${e.message}")
                            e.printStackTrace()
                        }
                    } else {
                        println("⚠️ Job [${job.id}] has no sourceSnapshot to replay.")
                    }
                }
            }
        )

        "JobListener initialized"
    },

    emptyList<String>() to { context, scriptPath, sentence, engine, _, logger ->
        engine.eval(sentence)
        val fn = engine.eval(sentence) as () -> Any
        fn()
    }
)
