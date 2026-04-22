package com.koupper.orchestrator

import com.koupper.orchestrator.config.JobConfig
import com.koupper.shared.runtime.ScriptingHostBackend
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Paths

object SchedulerRunner {
    fun runSchedules(
        context: String,
        jobId: String? = null,
        configId: String? = null,
        onResult: (List<Any?>) -> Unit = {}
    ) {
        val configs = JobConfig.loadOrFail(context, configId, "schedules.json")
        val allResults = mutableListOf<JobResult>()

        configs.configurations?.forEach { config ->
            if (config.ignoreOnProcessing) {
                return@forEach
            }

            val results = when (val driver = JobDrivers.resolve(config.driver)) {
                is ContextualJobDriver -> driver.forEachPending(context, config, jobId, "schedules")
                else -> driver.forEachPending(config, jobId, "schedules")
            }
            allResults += results
        }

        if (allResults.isEmpty()) {
            return onResult(listOf(JobResult.Error("⚠️ No jobs found")))
        }

        val backend = ScriptingHostBackend()

        val result = allResults.map { res ->
            when (res) {
                is JobResult.Ok -> {
                    val task = res.task
                    backend.eval(task.sourceSnapshot!!)
                    val result = ScriptRunner.runScript(task, backend.getSymbol(res.task.functionName))
                    JobInfo(
                        configId = res.configName,
                        id = res.task.id,
                        function = res.task.functionName,
                        params = res.task.params,
                        source = res.task.scriptPath,
                        context = res.task.context,
                        version = res.task.contextVersion,
                        origin = res.task.origin,
                        packg = res.task.packageName,
                        resultOfExecution = result
                    )
                }
                is JobResult.Error -> res
            }
        }

        onResult(result)
    }
}