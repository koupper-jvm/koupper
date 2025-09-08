package com.koupper.octopus

import com.koupper.octopus.logging.GlobalLogger
import com.koupper.octopus.logging.Logger
import com.koupper.octopus.process.JobEvent
import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.octopus.process.RoutesRegistration
import com.koupper.orchestrator.*
import com.koupper.shared.octopus.extractExportFunctionName
import java.io.File
import java.nio.file.Paths
import javax.script.ScriptEngine

val exportResolvers: Map<List<String>, (String, String, ScriptEngine, Map<String, Any>) -> Any> = mapOf(
    listOf("Map<String,Any>") to { context, sentence, engine, params ->
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (Map<String, Any>) -> Any
        fn(params)
    },
    listOf("ModuleProcessor") to { context, sentence, engine, params ->
        val processor = ModuleProcessor(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (com.koupper.octopus.process.Process) -> Any
        fn(processor)
    },
    listOf("ModuleProcessor", "Map<String,Any>") to { context, sentence, engine, params ->
        val processor = ModuleProcessor(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (com.koupper.octopus.process.Process, Map<String, Any>) -> Any
        fn(processor, params)
    },
    listOf("ModuleAnalyzer") to { context, sentence, engine, params ->
        val analyzer = ModuleAnalyzer(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (com.koupper.octopus.process.Process) -> Any
        fn(analyzer)
    },
    listOf("ModuleAnalyzer", "Map<String,Any>") to { context, sentence, engine, params ->
        val analyzer = ModuleAnalyzer(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (com.koupper.octopus.process.Process, Map<String, Any>) -> Any
        fn(analyzer, params)
    },
    listOf("RoutesRegistration") to { context, sentence, engine, params ->
        val routesRegistration = RoutesRegistration(params["context"] as String)
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (RoutesRegistration) -> Any
        fn(routesRegistration)
    },
    listOf("JobRunner") to { context, sentence, engine, params ->
        val runner = JobRunner
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (JobRunner) -> Any
        fn(runner)
    },
    listOf("JobLister") to { context, sentence, engine, params ->
        val runner = JobLister
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (JobLister) -> Any
        fn(runner)
    },
    listOf("JobBuilder") to { context, sentence, engine, params ->
        val runner = JobBuilder
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (JobBuilder) -> Any
        fn(runner)
    },
    listOf("JobDisplayer") to { context, sentence, engine, params ->
        val runner = JobDisplayer
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as (JobDisplayer) -> Any
        fn(runner)
    },
    emptyList<String>() to { context, sentence, engine, _ ->
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)) as () -> Any
        fn()
    }
)

val jobsListenerResolvers: Map<List<String>, (String, String, String, ScriptEngine, Map<String, Any>) -> Any> = mapOf(
    listOf("JobEvent") to handler@{ context, scriptPath, sentence, engine, params ->
        val log = params["dispatcherLogger"] as? Logger
            ?: error("Logger no encontrado en parámetros")

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
                GlobalLogger.setLogger(log)
                JobRunner.runPendingJobs(queue = cfgQueue, driver = cfgDriver) { job ->
                    onJob(job)
                }
            },
            onJob = { job ->
                GlobalLogger.setLogger(log)

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
                    GlobalLogger.setLogger(log)

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

    emptyList<String>() to { context, scriptPath, sentence, engine, _ ->
        engine.eval(sentence)
        val fn = engine.eval(sentence) as () -> Any
        fn()
    }
)
