package com.koupper.octopus.grpc

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit

/**
 * gRPC client for bidirectional job queue streaming.
 *
 * Connects to a remote Octopus daemon and participates in
 * real-time job dispatch with automatic reconnection.
 */
class JobQueueGrpcClient(
    private val host: String = "localhost",
    private val port: Int = 9996,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private var channel: ManagedChannel? = null
    private var stub: JobQueueGrpc.JobQueueStub? = null
    private val requestChannel = Channel<JobRequest>(Channel.BUFFERED)
    private val responseChannel = Channel<JobResponse>(Channel.BUFFERED)
    private var requestObserver: StreamObserver<JobRequest>? = null

    /**
     * Connects to the gRPC server with automatic reconnection.
     */
    fun connect() {
        channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build()

        stub = JobQueueGrpc.newStub(channel)

        startStreaming()
    }

    /**
     * Disconnects from the server gracefully.
     */
    fun disconnect() {
        requestObserver?.onCompleted()
        channel?.shutdown()
        scope.cancel()
    }

    /**
     * Submits a job request to the server.
     */
    suspend fun submitJob(request: JobRequest) {
        requestChannel.send(request)
    }

    /**
     * Flow of responses from the server.
     */
    fun responses(): Flow<JobResponse> = responseChannel.consumeAsFlow()

    private fun startStreaming() {
        requestObserver = stub!!.streamJobs(object : StreamObserver<JobResponse> {
            override fun onNext(response: JobResponse) {
                scope.launch {
                    responseChannel.send(response)
                }
            }

            override fun onError(t: Throwable) {
                println("gRPC client stream error: ${t.message}")
                scheduleReconnect()
            }

            override fun onCompleted() {
                println("gRPC server closed stream")
                scheduleReconnect()
            }
        })

        scope.launch {
            requestChannel.consumeAsFlow().collect { request ->
                requestObserver?.onNext(request)
            }
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(5000)
            println("Reconnecting to gRPC server...")
            runCatching { connect() }
        }
    }
}
