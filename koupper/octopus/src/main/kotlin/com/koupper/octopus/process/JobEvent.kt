package com.koupper.octopus.process

data class JobEvent(
    var jobId: String? = null,
    var queue: String? = null,
    var driver: String? = null,
    var function: String? = null,
    var context: String? = null,
    var contextVersion: String? = null,
    var origin: String? = null,
    var packageName: String? = null,
    var scriptPath: String? = null,
    var finishedAt: Long = System.currentTimeMillis()
)
