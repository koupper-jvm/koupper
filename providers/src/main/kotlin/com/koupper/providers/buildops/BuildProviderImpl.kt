package com.koupper.providers.buildops

import java.io.File
import java.util.concurrent.TimeUnit

class BuildProviderImpl : BuildProvider {

    override fun build(projectDir: File, tool: BuildTool, timeoutMs: Long): BuildResult {
        if (!projectDir.exists()) return projectNotFound(projectDir)
        val resolved = resolveTool(projectDir, tool) ?: return toolNotFound(projectDir)
        val cmd = when (resolved) {
            BuildTool.GRADLE -> listOf(gradlew(projectDir), "build", "-x", "test", "--console=plain")
            BuildTool.NPM    -> listOf("npm", "run", "build")
            BuildTool.AUTO   -> return toolNotFound(projectDir)
        }
        return execute(cmd, projectDir, timeoutMs)
    }

    override fun test(projectDir: File, filter: String?, tool: BuildTool, timeoutMs: Long): BuildResult {
        if (!projectDir.exists()) return projectNotFound(projectDir)
        val resolved = resolveTool(projectDir, tool) ?: return toolNotFound(projectDir)
        val cmd = when (resolved) {
            BuildTool.GRADLE -> buildList {
                add(gradlew(projectDir)); add("test"); add("--console=plain")
                if (filter != null) { add("--tests"); add(filter) }
            }
            BuildTool.NPM    -> listOf("npm", "test")
            BuildTool.AUTO   -> return toolNotFound(projectDir)
        }
        return execute(cmd, projectDir, timeoutMs)
    }

    override fun run(projectDir: File, vararg tasks: String, tool: BuildTool, timeoutMs: Long): BuildResult {
        if (!projectDir.exists()) return projectNotFound(projectDir)
        val resolved = resolveTool(projectDir, tool) ?: return toolNotFound(projectDir)
        val cmd = when (resolved) {
            BuildTool.GRADLE -> listOf(gradlew(projectDir), *tasks, "--console=plain")
            BuildTool.NPM    -> listOf("npm", "run", *tasks)
            BuildTool.AUTO   -> return toolNotFound(projectDir)
        }
        return execute(cmd, projectDir, timeoutMs)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveTool(dir: File, requested: BuildTool): BuildTool? = when (requested) {
        BuildTool.AUTO -> when {
            File(dir, "gradlew").exists() || File(dir, "gradlew.bat").exists() -> BuildTool.GRADLE
            File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists() -> BuildTool.GRADLE
            File(dir, "package.json").exists() -> BuildTool.NPM
            else -> null
        }
        else -> requested
    }

    private fun gradlew(dir: File): String =
        if (File(dir, "gradlew").exists()) "./gradlew" else "gradle"

    private fun execute(cmd: List<String>, dir: File, timeoutMs: Long): BuildResult {
        val start = System.currentTimeMillis()
        return runCatching {
            val proc = ProcessBuilder(cmd)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val timedOut = !proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (timedOut) {
                proc.destroyForcibly()
                return BuildResult.Err(
                    "Build timed out after ${timeoutMs}ms",
                    BuildErrorCode.TIMEOUT,
                    durationMs = System.currentTimeMillis() - start
                )
            }
            val output   = proc.inputStream.bufferedReader().readText()
            val duration = System.currentTimeMillis() - start
            val exitCode = proc.exitValue()
            if (exitCode == 0) {
                BuildResult.Success(output, duration)
            } else {
                val errors = parseErrors(output)
                BuildResult.Failure(errors, output, duration, exitCode)
            }
        }.getOrElse { e ->
            BuildResult.Err("IO error: ${e.message}", BuildErrorCode.IO_ERROR, durationMs = System.currentTimeMillis() - start)
        }
    }

    private fun parseErrors(output: String): List<BuildError> {
        val lines  = output.lines()
        val errors = mutableListOf<BuildError>()
        errors += KotlinParser.parse(lines)
        errors += TypeScriptParser.parse(lines)
        errors += NpmErrorParser.parse(lines)
        errors += GradleTaskParser.parse(lines)
        return errors.ifEmpty { listOf(BuildError(null, null, null, output.lines().lastOrNull { it.isNotBlank() } ?: "unknown error", Severity.ERROR, output)) }
    }

    private fun projectNotFound(dir: File) =
        BuildResult.Err("Project directory not found: ${dir.absolutePath}", BuildErrorCode.PROJECT_NOT_FOUND)

    private fun toolNotFound(dir: File) =
        BuildResult.Err("No build tool detected in: ${dir.absolutePath}", BuildErrorCode.TOOL_NOT_FOUND)
}

// ── Error parsers ─────────────────────────────────────────────────────────────

internal object KotlinParser {
    private val RE = Regex("""^[ew]: (?:file://)?(/[^:]+):(\d+):(\d+): (error|warning): (.+)$""")

    fun parse(lines: List<String>): List<BuildError> = lines.mapNotNull { line ->
        RE.matchEntire(line.trim())?.destructured?.let { (file, ln, col, sev, msg) ->
            BuildError(
                file     = file,
                line     = ln.toIntOrNull(),
                column   = col.toIntOrNull(),
                message  = msg,
                severity = if (sev == "error") Severity.ERROR else Severity.WARNING,
                raw      = line
            )
        }
    }
}

internal object TypeScriptParser {
    // file:line:col - error TSxxxx: message
    private val RE1 = Regex("""^(.+):(\d+):(\d+) - (error|warning) TS\d+: (.+)$""")
    // file(line,col): error TSxxxx: message
    private val RE2 = Regex("""^(.+)\((\d+),(\d+)\): (error|warning) TS\d+: (.+)$""")

    fun parse(lines: List<String>): List<BuildError> = lines.mapNotNull { line ->
        (RE1.matchEntire(line.trim()) ?: RE2.matchEntire(line.trim()))
            ?.destructured?.let { (file, ln, col, sev, msg) ->
                BuildError(
                    file     = file,
                    line     = ln.toIntOrNull(),
                    column   = col.toIntOrNull(),
                    message  = msg,
                    severity = if (sev == "error") Severity.ERROR else Severity.WARNING,
                    raw      = line
                )
            }
    }
}

internal object NpmErrorParser {
    private val RE = Regex("""^npm ERR! (.+)$""")

    fun parse(lines: List<String>): List<BuildError> = lines.mapNotNull { line ->
        RE.matchEntire(line.trim())?.destructured?.let { (msg) ->
            BuildError(null, null, null, msg, Severity.ERROR, line)
        }
    }
}

internal object GradleTaskParser {
    private val RE = Regex("""^> Task :(.+) FAILED$""")

    fun parse(lines: List<String>): List<BuildError> = lines.mapNotNull { line ->
        RE.matchEntire(line.trim())?.destructured?.let { (task) ->
            BuildError(null, null, null, "Task :$task FAILED", Severity.ERROR, line)
        }
    }
}
