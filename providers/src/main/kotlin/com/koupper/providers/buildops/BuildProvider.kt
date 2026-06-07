package com.koupper.providers.buildops

import java.io.File

enum class BuildTool { GRADLE, NPM, AUTO }
enum class Severity { ERROR, WARNING }
enum class BuildErrorCode { TOOL_NOT_FOUND, PROJECT_NOT_FOUND, TIMEOUT, IO_ERROR }

data class BuildError(
    val file: String?,
    val line: Int?,
    val column: Int?,
    val message: String,
    val severity: Severity,
    val raw: String
)

sealed class BuildResult {
    abstract val output: String
    abstract val durationMs: Long

    data class Success(
        override val output: String,
        override val durationMs: Long
    ) : BuildResult()

    data class Failure(
        val errors: List<BuildError>,
        override val output: String,
        override val durationMs: Long,
        val exitCode: Int
    ) : BuildResult() {
        val summary: String get() = errors.joinToString("\n") { e ->
            buildString {
                if (e.file != null) append("${e.file}")
                if (e.line != null) append(":${e.line}")
                if (e.column != null) append(":${e.column}")
                if (e.file != null || e.line != null) append(" — ")
                append("[${e.severity.name.lowercase()}] ${e.message}")
            }
        }
    }

    data class Err(
        val reason: String,
        val code: BuildErrorCode,
        override val output: String = "",
        override val durationMs: Long = 0
    ) : BuildResult()
}

interface BuildProvider {
    fun build(projectDir: File, tool: BuildTool = BuildTool.AUTO, timeoutMs: Long = 120_000): BuildResult
    fun test(projectDir: File, filter: String? = null, tool: BuildTool = BuildTool.AUTO, timeoutMs: Long = 300_000): BuildResult
    fun run(projectDir: File, vararg tasks: String, tool: BuildTool = BuildTool.AUTO, timeoutMs: Long = 120_000): BuildResult
}
