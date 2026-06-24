package com.koupper.octopus.grpc

import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC server for bidirectional job queue streaming.
 *
 * Enables real-time job dispatch and status updates between
 * Octopus daemon and remote workers/nodes.
 */
class JobQueueGrpcServer(
    private val port: Int = 9996,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private var server: Server? = null
    private val jobHandlers = ConcurrentHashMap<String, JobStreamHandler>()

    /**
     * Starts the gRPC server.
     */
    fun start() {
        server = ServerBuilder.forPort(port)
            .addService(JobQueueServiceImpl())
            .build()
            .start()

        println("gRPC JobQueue server started on port $port")
    }

    /**
     * Shuts down the server gracefully.
     */
    fun stop() {
        scope.cancel()
        server?.shutdown()
    }

    /**
     * Waits for the server to terminate.
     */
    fun blockUntilShutdown() {
        server?.awaitTermination()
    }

    inner class JobQueueServiceImpl : JobQueueGrpc.JobQueueImplBase() {
        override fun streamJobs(responseObserver: StreamObserver<JobResponse>): StreamObserver<JobRequest> {
            val handler = JobStreamHandler(responseObserver, scope)
            return handler.requestObserver
        }
    }

    /**
     * Handles a single bidirectional stream connection.
     */
    inner class JobStreamHandler(
        private val responseObserver: StreamObserver<JobResponse>,
        private val scope: CoroutineScope
    ) {
        private val requestChannel = Channel<JobRequest>(Channel.BUFFERED)
        private val responseChannel = Channel<JobResponse>(Channel.BUFFERED)

        val requestObserver: StreamObserver<JobRequest> = object : StreamObserver<JobRequest> {
            override fun onNext(request: JobRequest) {
                scope.launch {
                    requestChannel.send(request)
                }
            }

            override fun onError(t: Throwable) {
                println("gRPC stream error: ${t.message}")
                close()
            }

            override fun onCompleted() {
                close()
            }
        }

        init {
            scope.launch {
                // Process incoming requests
                requestChannel.consumeAsFlow().collect { request ->
                    processJob(request)
                }
            }

            scope.launch {
                // Forward responses to client
                responseChannel.consumeAsFlow().collect { response ->
                    responseObserver.onNext(response)
                }
            }
        }

        private suspend fun processJob(request: JobRequest) {
            // Echo back a RUNNING status immediately
            val runningResponse = JobResponse.newBuilder()
                .setJobId(request.jobId)
                .setStatus("RUNNING")
                .setTimestamp(System.currentTimeMillis())
                .build()
            responseChannel.send(runningResponse)

            // TODO: Integrate with actual job execution pipeline
            // For now, simulate completion
            delay(100)

            val completedResponse = JobResponse.newBuilder()
                .setJobId(request.jobId)
                .setStatus("COMPLETED")
                .setOutput("Job ${request.jobId} processed")
                .setTimestamp(System.currentTimeMillis())
                .build()
            responseChannel.send(completedResponse)
        }

        fun close() {
            requestChannel.close()
            responseChannel.close()
            responseObserver.onCompleted()
        }
    }
}
