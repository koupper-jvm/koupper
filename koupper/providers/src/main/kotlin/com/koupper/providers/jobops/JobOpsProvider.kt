package com.koupper.providers.jobops

import com.koupper.orchestrator.JobDrivers
import com.koupper.orchestrator.JobInfo
import com.koupper.orchestrator.JobResult
import com.koupper.orchestrator.JobRunner
import com.koupper.orchestrator.JobMetricsCollector
import com.koupper.orchestrator.JobSerializer
import com.koupper.orchestrator.config.JobConfig
import java.io.File

data class JobOpsStatusResult(
    val configId: String,
    val driver: String,
    val queue: String,
    val pending: Int,
    val inFlight: Int
)

data class JobOpsPendingItem(
    val configId: String,
    val id: String,
    val function: String,
    val params: Map<String, Any?>,
    val source: String?
)

data class JobOpsRunWorkerResult(
    val configId: String?,
    val processed: Int,
    val ok: Int,
    val failed: Int,
    val details: List<Map<String, Any?>>
)

data class JobOpsFailedItem(
    val configId: String,
    val queue: String,
    val id: String,
    val function: String,
    val context: String?
)

data class JobOpsRetryResult(
    val configId: String,
    val queue: String,
    val jobId: String,
    val retried: Boolean,
    val reason: String? = null
)

interface JobOps {
    fun status(context: String, configId: String? = null): List<JobOpsStatusResult>
    fun listPending(context: String, configId: String? = null, jobId: String? = null): List<JobOpsPendingItem>
    fun runWorker(context: String, configId: String? = null, jobId: String? = null): List<JobOpsRunWorkerResult>
    fun failed(context: String, configId: String? = null, jobId: String? = null): List<JobOpsFailedItem>
    fun retry(context: String, configId: String? = null, jobId: String): List<JobOpsRetryResult>
}

class DefaultJobOps : JobOps {
    override fun status(context: String, configId: String?): List<JobOpsStatusResult> {
        val loaded = JobConfig.loadOrFail(context, configId).configurations.orEmpty()
        return loaded.map { cfg ->
            val metrics = JobMetricsCollector.collect(context, cfg)
            JobOpsStatusResult(
                configId = cfg.id ?: "default",
                driver = cfg.driver,
                queue = cfg.queue ?: "default",
                pending = metrics.pending,
                inFlight = metrics.inFlight ?: 0
            )
        }
    }

    override fun listPending(context: String, configId: String?, jobId: String?): List<JobOpsPendingItem> {
        val loaded = JobConfig.loadOrFail(context, configId).configurations.orEmpty()
        val output = mutableListOf<JobOpsPendingItem>()

        loaded.forEach { cfg ->
            val driver = JobDrivers.resolve(cfg.driver)
            val results = driver.listPending(context, cfg, jobId)
            results.forEach { result ->
                if (result is JobResult.Ok) {
                    output += JobOpsPendingItem(
                        configId = cfg.id ?: "default",
                        id = result.task.id,
                        function = result.task.functionName,
                        params = result.task.params,
                        source = result.task.scriptPath
                    )
                }
            }
        }

        return output
    }

    override fun runWorker(context: String, configId: String?, jobId: String?): List<JobOpsRunWorkerResult> {
        val loaded = JobConfig.loadOrFail(context, configId).configurations.orEmpty()
        val results = mutableListOf<JobOpsRunWorkerResult>()

        loaded.forEach { cfg ->
            val driver = JobDrivers.resolve(cfg.driver)
            val pending = when (driver) {
                is com.koupper.orchestrator.ContextualJobDriver -> driver.forEachPending(context, cfg, jobId)
                else -> driver.forEachPending(cfg, jobId)
            }

            val details = mutableListOf<Map<String, Any?>>()
            var ok = 0
            var failed = 0

            pending.forEach { p ->
                when (p) {
                    is JobResult.Ok -> {
                        val task = p.task
                        val taskContext = task.context?.takeIf { it.isNotBlank() } ?: context
                        val execution = runCatching { JobRunner.runCompiled(taskContext, task) }
                        if (execution.isSuccess) {
                            ok += 1
                            details += mapOf(
                                "jobId" to task.id,
                                "configId" to (cfg.id ?: "default"),
                                "ok" to true,
                                "result" to execution.getOrNull()
                            )
                        } else {
                            failed += 1
                            details += mapOf(
                                "jobId" to task.id,
                                "configId" to (cfg.id ?: "default"),
                                "ok" to false,
                                "error" to (execution.exceptionOrNull()?.message ?: "runCompiled failed")
                            )
                        }
                    }

                    is JobResult.Error -> {
                        failed += 1
                        details += mapOf(
                            "jobId" to null,
                            "configId" to (cfg.id ?: "default"),
                            "ok" to false,
                            "error" to p.message
                        )
                    }
                }
            }

            results += JobOpsRunWorkerResult(
                configId = cfg.id,
                processed = pending.size,
                ok = ok,
                failed = failed,
                details = details
            )
        }

        return results
    }

    override fun failed(context: String, configId: String?, jobId: String?): List<JobOpsFailedItem> {
        val loaded = JobConfig.loadOrFail(context, configId).configurations.orEmpty()
        val items = mutableListOf<JobOpsFailedItem>()

        loaded.forEach { cfg ->
            if (cfg.driver != "file") return@forEach
            val queue = cfg.queue ?: "default"
            val failedDir = File("$context/jobs/$queue/.failed")
            val files = failedDir.listFiles { f -> f.isFile && f.extension.equals("json", true) }.orEmpty()
            files.forEach { file ->
                runCatching {
                    val task = JobSerializer.deserialize(file.readText(Charsets.UTF_8))
                    if (jobId == null || task.id == jobId) {
                        items += JobOpsFailedItem(
                            configId = cfg.id ?: "default",
                            queue = queue,
                            id = task.id,
                            function = task.functionName,
                            context = task.context
                        )
                    }
                }
            }
        }

        return items
    }

    override fun retry(context: String, configId: String?, jobId: String): List<JobOpsRetryResult> {
        require(jobId.isNotBlank()) { "jobId is required" }
        val loaded = JobConfig.loadOrFail(context, configId).configurations.orEmpty()
        val results = mutableListOf<JobOpsRetryResult>()

        loaded.forEach { cfg ->
            val queue = cfg.queue ?: "default"
            if (cfg.driver != "file") {
                results += JobOpsRetryResult(
                    configId = cfg.id ?: "default",
                    queue = queue,
                    jobId = jobId,
                    retried = false,
                    reason = "retry currently supported only for file driver"
                )
                return@forEach
            }

            val source = File("$context/jobs/$queue/.failed/${if (jobId.endsWith(".json")) jobId else "$jobId.json"}")
            val target = File("$context/jobs/$queue/${source.name}")
            val retried = if (source.exists()) {
                source.copyTo(target, overwrite = true)
                source.delete()
                true
            } else {
                false
            }

            results += JobOpsRetryResult(
                configId = cfg.id ?: "default",
                queue = queue,
                jobId = jobId,
                retried = retried,
                reason = if (retried) null else "failed job not found"
            )
        }

        return results
    }
}
