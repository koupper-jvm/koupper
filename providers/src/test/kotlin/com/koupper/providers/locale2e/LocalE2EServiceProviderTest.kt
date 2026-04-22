package com.koupper.providers.locale2e

import com.koupper.providers.aws.dynamo.DynamoLocalAdmin
import com.koupper.providers.jobops.JobOps
import com.koupper.providers.jobops.JobOpsFailedItem
import com.koupper.providers.jobops.JobOpsPendingItem
import com.koupper.providers.jobops.JobOpsRetryResult
import com.koupper.providers.jobops.JobOpsRunWorkerResult
import com.koupper.providers.jobops.JobOpsStatusResult
import com.koupper.providers.process.ProcessCleanupResult
import com.koupper.providers.process.ProcessHealthResult
import com.koupper.providers.process.ProcessListItem
import com.koupper.providers.process.ProcessListRequest
import com.koupper.providers.process.ProcessLogsRequest
import com.koupper.providers.process.ProcessLogsResult
import com.koupper.providers.process.ProcessStartRequest
import com.koupper.providers.process.ProcessStartResult
import com.koupper.providers.process.ProcessStatusManyRequest
import com.koupper.providers.process.ProcessStatusRequest
import com.koupper.providers.process.ProcessStatusResult
import com.koupper.providers.process.ProcessStopManyRequest
import com.koupper.providers.process.ProcessStopRequest
import com.koupper.providers.process.ProcessStopResult
import com.koupper.providers.process.ProcessSupervisor
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalE2EServiceProviderTest : AnnotationSpec() {
    @Test
    fun `run all should aggregate stages`() {
        val flow = DefaultLocalE2E(
            processSupervisor = fakeProcessSupervisor(),
            jobOps = fakeJobOps(),
            dynamoLocalAdmin = fakeDynamoLocalAdmin()
        )

        val result = flow.runAll(
            processNames = listOf("api"),
            context = ".",
            persistenceChecks = listOf(PersistenceCheck(table = "users", expectedMinCount = 1))
        )

        assertTrue(result.ok)
        assertEquals(3, result.stages.size)
    }
}

private fun fakeProcessSupervisor(): ProcessSupervisor = object : ProcessSupervisor {
    override fun start(request: ProcessStartRequest): ProcessStartResult =
        ProcessStartResult(1L, request.name, 1L, "log", "cmd")

    override fun startMany(requests: List<ProcessStartRequest>): List<ProcessStartResult> =
        requests.map { start(it) }

    override fun status(request: ProcessStatusRequest): ProcessStatusResult =
        ProcessStatusResult(
            name = request.name ?: "svc",
            running = true,
            pid = request.pid ?: 1L,
            startedAt = 1L,
            command = "cmd",
            logPath = "log",
            health = ProcessHealthResult(url = "http://localhost", healthy = true, responseTimeMs = 1, attempts = 1)
        )

    override fun statusMany(request: ProcessStatusManyRequest): List<ProcessStatusResult> =
        request.names.map { name -> status(ProcessStatusRequest(name = name)) }

    override fun list(): List<ProcessListItem> = emptyList()

    override fun list(request: ProcessListRequest): List<ProcessListItem> = emptyList()

    override fun stop(request: ProcessStopRequest): ProcessStopResult =
        ProcessStopResult(name = request.name ?: "svc", pid = request.pid ?: 1L, stopped = true, wasRunning = true, force = request.force)

    override fun stopMany(request: ProcessStopManyRequest): List<ProcessStopResult> =
        request.names.map { name -> stop(ProcessStopRequest(name = name, force = request.force)) }

    override fun logs(request: ProcessLogsRequest): ProcessLogsResult =
        ProcessLogsResult(name = request.name, pid = request.pid, logPath = request.path ?: "log", truncated = false, lines = emptyList())

    override fun cleanup(): ProcessCleanupResult = ProcessCleanupResult(0, 0, emptyList())
}

private fun fakeJobOps(): JobOps = object : JobOps {
    override fun status(context: String, configId: String?): List<JobOpsStatusResult> = emptyList()
    override fun listPending(context: String, configId: String?, jobId: String?): List<JobOpsPendingItem> = emptyList()
    override fun runWorker(context: String, configId: String?, jobId: String?): List<JobOpsRunWorkerResult> =
        listOf(JobOpsRunWorkerResult(configId = configId, processed = 0, ok = 0, failed = 0, details = emptyList()))

    override fun failed(context: String, configId: String?, jobId: String?): List<JobOpsFailedItem> = emptyList()
    override fun retry(context: String, configId: String?, jobId: String): List<JobOpsRetryResult> = emptyList()
}

private fun fakeDynamoLocalAdmin(): DynamoLocalAdmin = object : DynamoLocalAdmin {
    override fun ensureTable(spec: com.koupper.providers.aws.dynamo.DynamoTableSpec): Boolean = true
    override fun tableExists(name: String): Boolean = true
    override fun truncateTable(name: String, keySchema: List<String>): Int = 0
    override fun countByEmail(table: String, email: String, field: String): Int = 0
    override fun scanCount(
        table: String,
        filterExpression: String?,
        expressionValues: Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>?
    ): Int = 1

    override fun listTables(): List<String> = emptyList()
}
