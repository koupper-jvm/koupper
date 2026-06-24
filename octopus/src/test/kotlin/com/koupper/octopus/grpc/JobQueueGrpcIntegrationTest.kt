package com.koupper.octopus.grpc

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

class JobQueueGrpcIntegrationTest : ShouldSpec({

    should("start server and exchange bidirectional messages") {
        val server = JobQueueGrpcServer(port = 9995)
        server.start()

        val client = JobQueueGrpcClient(host = "localhost", port = 9995)
        client.connect()

        // Give connection time to establish
        delay(500)

        val request = JobRequest.newBuilder()
            .setJobId("test-job-1")
            .setQueue("default")
            .setScriptPath("test.kts")
            .setPayload("{}")
            .build()

        client.submitJob(request)

        val responses = client.responses().take(2).toList()

        responses[0].status shouldBe "RUNNING"
        responses[0].jobId shouldBe "test-job-1"

        responses[1].status shouldBe "COMPLETED"
        responses[1].output shouldContain "test-job-1"

        client.disconnect()
        server.stop()
    }

    should("handle multiple jobs concurrently") {
        val server = JobQueueGrpcServer(port = 9994)
        server.start()

        val client = JobQueueGrpcClient(host = "localhost", port = 9994)
        client.connect()
        delay(500)

        val jobs = (1..3).map { i ->
            JobRequest.newBuilder()
                .setJobId("job-$i")
                .setQueue("default")
                .setScriptPath("test$i.kts")
                .setPayload("{}")
                .build()
        }

        jobs.forEach { client.submitJob(it) }

        val responses = client.responses().take(6).toList() // 2 per job
        val completed = responses.filter { it.status == "COMPLETED" }

        completed.size shouldBe 3

        client.disconnect()
        server.stop()
    }
})
