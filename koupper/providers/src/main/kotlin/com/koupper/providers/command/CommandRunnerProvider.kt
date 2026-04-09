package com.koupper.providers.command

import java.io.File
import java.util.concurrent.TimeUnit

data class CommandRunRequest(
    val executable: String? = null,
    val args: List<String> = emptyList(),
    val shellCommand: String? = null,
    val workingDirectory: String = ".",
    val environment: Map<String, String> = emptyMap(),
    val timeoutSeconds: Long = 300,
    val dryRun: Boolean = false,
    val maskValues: List<String> = emptyList()
)

data class CommandRunResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val dryRun: Boolean = false
)

interface CommandRunner {
    fun run(request: CommandRunRequest): CommandRunResult
    fun runChecked(request: CommandRunRequest): CommandRunResult
}

class DefaultCommandRunner(
    private val defaultTimeoutSeconds: Long = 300,
    private val windowsShell: String = "pwsh",
    private val unixShell: String = "bash"
) : CommandRunner {

    override fun run(request: CommandRunRequest): CommandRunResult {
        val commandParts = commandParts(request)
        val printable = redact(renderCommand(request), request.maskValues)

        if (request.dryRun) {
            return CommandRunResult(
                command = printable,
                exitCode = 0,
                stdout = "dry-run",
                stderr = "",
                durationMs = 0,
                timedOut = false,
                dryRun = true
            )
        }

        val startedAt = System.currentTimeMillis()
        val processBuilder = ProcessBuilder(commandParts)
            .directory(File(request.workingDirectory))
        processBuilder.environment().putAll(request.environment)
        val process = processBuilder.start()

        val timeout = if (request.timeoutSeconds > 0) request.timeoutSeconds else defaultTimeoutSeconds
        val completed = process.waitFor(timeout, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return CommandRunResult(
                command = printable,
                exitCode = 124,
                stdout = "",
                stderr = "command timed out after ${timeout}s",
                durationMs = System.currentTimeMillis() - startedAt,
                timedOut = true,
                dryRun = false
            )
        }

        return CommandRunResult(
            command = printable,
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim(),
            durationMs = System.currentTimeMillis() - startedAt,
            timedOut = false,
            dryRun = false
        )
    }

    override fun runChecked(request: CommandRunRequest): CommandRunResult {
        val result = run(request)
        if (result.exitCode != 0) {
            val detail = result.stderr.ifBlank { result.stdout }.ifBlank { "exit code ${result.exitCode}" }
            error("Command failed (${result.command}): $detail")
        }
        return result
    }

    private fun commandParts(request: CommandRunRequest): List<String> {
        val executable = request.executable?.trim().orEmpty()
        val shellCommand = request.shellCommand?.trim().orEmpty()
        val hasExecutable = executable.isNotBlank()
        val hasShellCommand = shellCommand.isNotBlank()

        if (hasExecutable == hasShellCommand) {
            error("Provide exactly one of executable or shellCommand")
        }

        return if (hasShellCommand) {
            if (isWindows()) listOf(windowsShell, "-NoProfile", "-Command", shellCommand)
            else listOf(unixShell, "-lc", shellCommand)
        } else {
            listOf(executable) + request.args
        }
    }

    private fun renderCommand(request: CommandRunRequest): String {
        val executable = request.executable?.trim().orEmpty()
        val shellCommand = request.shellCommand?.trim().orEmpty()
        return if (shellCommand.isNotBlank()) shellCommand else listOf(executable).plus(request.args).joinToString(" ")
    }

    private fun redact(value: String, maskValues: List<String>): String {
        return maskValues.filter { it.isNotBlank() }.fold(value) { acc, v -> acc.replace(v, "***") }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
