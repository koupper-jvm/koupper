package com.koupper.octopus.monitoring

import com.koupper.container.app
import com.koupper.providers.files.JSONFileHandler
import com.koupper.shared.monitoring.ExecutionMeta
import com.koupper.shared.monitoring.ExecutionMonitor
import java.io.File

class ResumenArchivosExecutionMonitor : ExecutionMonitor {
    override fun <T> track(meta: ExecutionMeta, block: () -> T): T = block()

    override fun reportPayload(key: String, payload: Any) {
        val outputFile = File(System.getProperty("user.home"), ".koupper/helpers/$key.json")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(app.getInstance(JSONFileHandler::class).toJson(payload))
    }
}
