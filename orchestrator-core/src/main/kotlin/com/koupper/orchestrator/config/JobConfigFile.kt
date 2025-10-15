package com.koupper.orchestrator.config

import com.fasterxml.jackson.annotation.JsonProperty

data class JobDriverConnection(
    val type: String,
    val options: Map<String, String>
)

data class QueueConfig(
    val concurrency: Int
)

data class JobConfiguration(
    val id: String? = null,
    val driver: String = "file",
    val queue: String? = "default",

    @field:JsonProperty("sqs-region")
    val sqsRegion: String? = null,

    @field:JsonProperty("sqs-queue-url")
    val sqsQueueUrl: String? = null,

    @field:JsonProperty("sqs-access-key")
    val sqsAccessKey: String? = null,

    @field:JsonProperty("sqs-secret-key")
    val sqsSecretKey: String? = null,

    @field:JsonProperty("redis-host")
    val redisHost: String? = null,

    @field:JsonProperty("redis-port")
    val redisPort: String? = null,

    @field:JsonProperty("redis-password")
    val redisPassword: String? = null,

    @field:JsonProperty("database-url")
    val databaseUrl: String? = null,

    @field:JsonProperty("database-user")
    val databaseUser: String? = null,

    @field:JsonProperty("database-password")
    val databasePassword: String? = null,

    @field:JsonProperty("for-all-projects")
    val forAllProjects: Boolean = false,

    @field:JsonProperty("ignore-on-processing")
    val ignoreOnProcessing: Boolean = false,

    val configurations: List<JobConfiguration>? = null
)

