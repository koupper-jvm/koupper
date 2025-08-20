package com.koupper.orchestrator.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Paths

object JobConfig {
    fun loadOrFail(): JobConfigFile {
        val file = File("${Paths.get("").toAbsolutePath()}/jobs.json")
        if (!file.exists()) {
            throw IllegalStateException(
                "Missing jobs.json. If you're using jobs, make sure to generate the required setup by running: `koupper job init`."
            )
        }

        return jacksonObjectMapper().readValue(file, JobConfigFile::class.java)
    }
}
