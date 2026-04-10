package com.koupper.providers.locale2e

import com.koupper.providers.aws.dynamo.DynamoLocalAdmin
import com.koupper.providers.jobops.JobOps
import com.koupper.providers.process.ProcessStatusManyRequest
import com.koupper.providers.process.ProcessSupervisor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class HttpCheck(
    val name: String,
    val url: String,
    val method: String = "GET",
    val acceptedStatusCodes: Set<Int> = setOf(200),
    val timeoutMs: Long = 1500
)

data class PersistenceCheck(
    val table: String,
    val expectedMinCount: Int = 1,
    val filterExpression: String? = null
)

data class StageResult(
    val stage: String,
    val ok: Boolean,
    val details: Map<String, Any?>
)

data class LocalE2EResult(
    val ok: Boolean,
    val stages: List<StageResult>
)

interface LocalE2E {
    fun runHttpChecks(checks: List<HttpCheck>): StageResult
    fun runJobCycle(context: String, configId: String? = null, jobId: String? = null): StageResult
    fun verifyPersistence(checks: List<PersistenceCheck>): StageResult
    fun runAll(
        processNames: List<String> = emptyList(),
        httpChecks: List<HttpCheck> = emptyList(),
        context: String,
        configId: String? = null,
        jobId: String? = null,
        persistenceChecks: List<PersistenceCheck> = emptyList()
    ): LocalE2EResult
}

class DefaultLocalE2E(
    private val processSupervisor: ProcessSupervisor,
    private val jobOps: JobOps,
    private val dynamoLocalAdmin: DynamoLocalAdmin
) : LocalE2E {
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    override fun runHttpChecks(checks: List<HttpCheck>): StageResult {
        val details = checks.map { check ->
            val started = System.currentTimeMillis()
            val result = runCatching {
                val request = HttpRequest.newBuilder(URI.create(check.url))
                    .timeout(Duration.ofMillis(check.timeoutMs.coerceAtLeast(1)))
                    .method(check.method.uppercase(), HttpRequest.BodyPublishers.noBody())
                    .build()
                httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            }

            if (result.isSuccess) {
                val response = result.getOrThrow()
                mapOf(
                    "name" to check.name,
                    "ok" to (response.statusCode() in check.acceptedStatusCodes),
                    "status" to response.statusCode(),
                    "elapsedMs" to (System.currentTimeMillis() - started)
                )
            } else {
                mapOf(
                    "name" to check.name,
                    "ok" to false,
                    "status" to null,
                    "elapsedMs" to (System.currentTimeMillis() - started),
                    "error" to (result.exceptionOrNull()?.message ?: "http check failed")
                )
            }
        }

        return StageResult(
            stage = "http",
            ok = details.all { it["ok"] == true },
            details = mapOf("checks" to details)
        )
    }

    override fun runJobCycle(context: String, configId: String?, jobId: String?): StageResult {
        val before = jobOps.status(context, configId)
        val run = jobOps.runWorker(context, configId, jobId)
        val after = jobOps.status(context, configId)

        val ok = run.all { it.failed == 0 }
        return StageResult(
            stage = "jobs",
            ok = ok,
            details = mapOf(
                "before" to before,
                "run" to run,
                "after" to after
            )
        )
    }

    override fun verifyPersistence(checks: List<PersistenceCheck>): StageResult {
        val results = checks.map { check ->
            val count = dynamoLocalAdmin.scanCount(
                table = check.table,
                filterExpression = check.filterExpression,
                expressionValues = null
            )

            mapOf(
                "table" to check.table,
                "count" to count,
                "expectedMinCount" to check.expectedMinCount,
                "ok" to (count >= check.expectedMinCount)
            )
        }

        return StageResult(
            stage = "persistence",
            ok = results.all { it["ok"] == true },
            details = mapOf("checks" to results)
        )
    }

    override fun runAll(
        processNames: List<String>,
        httpChecks: List<HttpCheck>,
        context: String,
        configId: String?,
        jobId: String?,
        persistenceChecks: List<PersistenceCheck>
    ): LocalE2EResult {
        val stages = mutableListOf<StageResult>()

        if (processNames.isNotEmpty()) {
            val processStatus = processSupervisor.statusMany(ProcessStatusManyRequest(names = processNames))
            stages += StageResult(
                stage = "processes",
                ok = processStatus.all { it.running },
                details = mapOf("status" to processStatus)
            )
        }

        if (httpChecks.isNotEmpty()) stages += runHttpChecks(httpChecks)
        stages += runJobCycle(context, configId, jobId)
        if (persistenceChecks.isNotEmpty()) stages += verifyPersistence(persistenceChecks)

        return LocalE2EResult(
            ok = stages.all { it.ok },
            stages = stages
        )
    }
}
