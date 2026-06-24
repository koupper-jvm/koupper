import com.koupper.octopus.grpc.JobQueueGrpcClient
import com.koupper.octopus.grpc.JobRequest

/**
 * gRPC client example — submits a job to the JobQueue server.
 *
 * Requires the gRPC server running on port 9996:
 *   JobQueueGrpcServer(port = 9996).start()
 */
fun main() {
    val client = JobQueueGrpcClient(host = "localhost", port = 9996)
    client.connect()

    val request = JobRequest.newBuilder()
        .setJobId("job-example-1")
        .setQueue("default")
        .setScriptPath("examples/scripts/basic-export.kts")
        .setPayload("{\"key\": \"value\"}")
        .putMetadata("priority", "high")
        .build()

    // In a real coroutine context:
    // runBlocking { client.submitJob(request) }

    println("Submitted job ${request.jobId} to queue ${request.queue}")

    client.disconnect()
}
