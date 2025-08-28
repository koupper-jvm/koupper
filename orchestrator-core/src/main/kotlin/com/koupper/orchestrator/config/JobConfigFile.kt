package com.koupper.orchestrator.config

data class JobDriverConnection(
    val type: String,
    val options: Map<String, String>
)

data class QueueConfig(
    val concurrency: Int
)

data class JobConfigFile(
    val queue: String = "default",
    val driver: String = "file",
    val queues: Map<String, QueueConfig> = emptyMap(),
    val connections: Map<String, JobDriverConnection> = emptyMap()
)
