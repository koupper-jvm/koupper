package com.koupper.shared.monitoring

import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class ExecutionStatus {
    OK,
    ERROR
}

data class ExecutionMeta(
        val exportId: String,
        val kind: String, // "KTS" | "KT"
        val context: String? = null,
        val scriptPath: String? = null
)

interface ExecutionMonitor {
    fun <T> track(meta: ExecutionMeta, block: () -> T): T
    fun reportPayload(key: String, payload: Any)
}

object NoopExecutionMonitor : ExecutionMonitor {
    override fun <T> track(meta: ExecutionMeta, block: () -> T): T = block()
    
    override fun reportPayload(key: String, payload: Any) {
        // No-op
    }
}

class JsonlExecutionMonitor(private val file: File) : ExecutionMonitor {
    private val lock = ReentrantLock()

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
    }

    override fun reportPayload(key: String, payload: Any) {
        appendLine(
            """{"ts":"${OffsetDateTime.now()}","type":"payload","key":"${esc(key)}","payload":${qs(payload.toString())}}"""
        )
    }

    override fun <T> track(meta: ExecutionMeta, block: () -> T): T {
        val executionId = UUID.randomUUID().toString()
        val startNs = System.nanoTime()

        return try {
            val out = block()
            val ms = (System.nanoTime() - startNs) / 1_000_000
            appendLine(
                    """{"ts":"${OffsetDateTime.now()}","executionId":"$executionId","exportId":"${esc(meta.exportId)}","kind":"${esc(meta.kind)}","context":${qs(meta.context)},"scriptPath":${qs(meta.scriptPath)},"status":"OK","durationMs":$ms}"""
            )
            out
        } catch (t: Throwable) {
            val ms = (System.nanoTime() - startNs) / 1_000_000
            appendLine(
                    """{"ts":"${OffsetDateTime.now()}","executionId":"$executionId","exportId":"${esc(meta.exportId)}","kind":"${esc(meta.kind)}","context":${qs(meta.context)},"scriptPath":${qs(meta.scriptPath)},"status":"ERROR","durationMs":$ms,"errorMessage":"${esc(t.message ?: t::class.qualifiedName ?: "unknown")}"}"""
            )
            throw t
        }
    }

    private fun appendLine(line: String) = lock.withLock { file.appendText(line + "\n") }

    private fun esc(s: String): String =
            s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")

    private fun qs(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""
}
