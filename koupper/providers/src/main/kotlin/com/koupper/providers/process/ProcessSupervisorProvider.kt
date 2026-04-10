package com.koupper.providers.process

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ProcessStartRequest(
    val name: String,
    val executable: String? = null,
    val args: List<String> = emptyList(),
    val shellCommand: String? = null,
    val workingDirectory: String = ".",
    val environment: Map<String, String> = emptyMap(),
    val healthUrl: String? = null,
    val healthTimeoutMs: Long = 1500,
    val appendLogs: Boolean = true
)

data class ProcessStartResult(
    val processId: Long,
    val name: String,
    val startedAt: Long,
    val logPath: String,
    val command: String,
    val alreadyRunning: Boolean = false
)

data class ProcessStatusRequest(
    val name: String? = null,
    val pid: Long? = null,
    val healthUrl: String? = null,
    val healthTimeoutMs: Long = 1500
)

data class ProcessHealthResult(
    val url: String,
    val healthy: Boolean,
    val statusCode: Int? = null,
    val responseTimeMs: Long,
    val error: String? = null
)

data class ProcessStatusResult(
    val name: String,
    val running: Boolean,
    val pid: Long,
    val exitCode: Int? = null,
    val uptimeMs: Long? = null,
    val startedAt: Long,
    val command: String,
    val logPath: String,
    val health: ProcessHealthResult? = null
)

data class ProcessListItem(
    val name: String,
    val pid: Long,
    val running: Boolean,
    val startedAt: Long,
    val command: String,
    val logPath: String,
    val healthUrl: String? = null
)

data class ProcessStopRequest(
    val name: String? = null,
    val pid: Long? = null,
    val force: Boolean = false,
    val waitTimeoutMs: Long = 10000
)

data class ProcessStopResult(
    val name: String,
    val pid: Long,
    val stopped: Boolean,
    val wasRunning: Boolean,
    val force: Boolean,
    val exitCode: Int? = null
)

data class ProcessLogsRequest(
    val name: String? = null,
    val pid: Long? = null,
    val path: String? = null,
    val tailLines: Int = 200
)

data class ProcessLogsResult(
    val name: String? = null,
    val pid: Long? = null,
    val logPath: String,
    val lines: List<String>
)

data class ProcessCleanupResult(
    val removed: Int,
    val remaining: Int,
    val removedNames: List<String>
)

interface ProcessSupervisor {
    fun start(request: ProcessStartRequest): ProcessStartResult
    fun status(request: ProcessStatusRequest): ProcessStatusResult
    fun list(): List<ProcessListItem>
    fun stop(request: ProcessStopRequest): ProcessStopResult
    fun logs(request: ProcessLogsRequest): ProcessLogsResult
    fun cleanup(): ProcessCleanupResult
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ProcessRegistry(
    val version: String = "1.0",
    val processes: List<StoredProcess> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class StoredProcess(
    val name: String,
    val pid: Long,
    val command: String,
    val workingDirectory: String,
    val envKeys: List<String>,
    val startedAt: Long,
    val logPath: String,
    val healthUrl: String? = null,
    val stoppedAt: Long? = null,
    val lastKnownExitCode: Int? = null
)

class LocalProcessSupervisor(
    private val storePath: String = "${System.getProperty("user.home")}/.koupper/processes.json",
    private val logsDirectory: String = "${System.getProperty("user.home")}/.koupper/process-logs",
    private val windowsShell: String = "pwsh",
    private val unixShell: String = "bash"
) : ProcessSupervisor {
    private val mapper = jacksonObjectMapper()
    private val lock = Any()
    private val managedProcesses = ConcurrentHashMap<Long, Process>()
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    override fun start(request: ProcessStartRequest): ProcessStartResult {
        val normalizedName = request.name.trim()
        require(normalizedName.isNotEmpty()) { "name is required" }

        synchronized(lock) {
            val registry = readRegistry()
            val existing = registry.processes.firstOrNull { it.name == normalizedName }
            if (existing != null && isRunning(existing.pid)) {
                return ProcessStartResult(
                    processId = existing.pid,
                    name = existing.name,
                    startedAt = existing.startedAt,
                    logPath = existing.logPath,
                    command = existing.command,
                    alreadyRunning = true
                )
            }

            val processFile = prepareLogFile(normalizedName, request.appendLogs)
            val commandParts = buildCommandParts(request)
            val renderedCommand = renderCommand(request)
            val builder = ProcessBuilder(commandParts)
                .directory(File(request.workingDirectory))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(processFile))
                .redirectError(ProcessBuilder.Redirect.appendTo(processFile))

            builder.environment().putAll(request.environment)

            val process = builder.start()
            val startedAt = System.currentTimeMillis()
            val record = StoredProcess(
                name = normalizedName,
                pid = process.pid(),
                command = renderedCommand,
                workingDirectory = request.workingDirectory,
                envKeys = request.environment.keys.sorted(),
                startedAt = startedAt,
                logPath = processFile.absolutePath,
                healthUrl = request.healthUrl?.takeIf { it.isNotBlank() }
            )

            managedProcesses[process.pid()] = process

            val updated = registry.processes
                .filterNot { it.name == normalizedName }
                .plus(record)
                .sortedBy { it.name }

            writeRegistry(ProcessRegistry(processes = updated))

            return ProcessStartResult(
                processId = process.pid(),
                name = normalizedName,
                startedAt = startedAt,
                logPath = processFile.absolutePath,
                command = renderedCommand,
                alreadyRunning = false
            )
        }
    }

    override fun status(request: ProcessStatusRequest): ProcessStatusResult {
        val record = resolveRecord(request.name, request.pid)
        val running = isRunning(record.pid)
        val now = System.currentTimeMillis()
        val uptime = if (running) now - record.startedAt else null

        val healthTarget = request.healthUrl?.takeIf { it.isNotBlank() } ?: record.healthUrl
        val health = healthTarget?.let { doHealthCheck(it, request.healthTimeoutMs) }

        return ProcessStatusResult(
            name = record.name,
            running = running,
            pid = record.pid,
            exitCode = if (running) null else resolveExitCode(record.pid, record.lastKnownExitCode),
            uptimeMs = uptime,
            startedAt = record.startedAt,
            command = record.command,
            logPath = record.logPath,
            health = health
        )
    }

    override fun list(): List<ProcessListItem> {
        return readRegistry().processes.map { record ->
            ProcessListItem(
                name = record.name,
                pid = record.pid,
                running = isRunning(record.pid),
                startedAt = record.startedAt,
                command = record.command,
                logPath = record.logPath,
                healthUrl = record.healthUrl
            )
        }
    }

    override fun stop(request: ProcessStopRequest): ProcessStopResult {
        synchronized(lock) {
            val registry = readRegistry()
            val record = resolveRecord(request.name, request.pid)
            val handle = ProcessHandle.of(record.pid).orElse(null)
            val wasRunning = handle?.isAlive == true

            if (wasRunning) {
                if (request.force) handle?.destroyForcibly() else handle?.destroy()
                runCatching { handle?.onExit()?.get(request.waitTimeoutMs, TimeUnit.MILLISECONDS) }
            }

            val stopped = handle?.isAlive != true
            val exitCode = resolveExitCode(record.pid, record.lastKnownExitCode)

            val updatedRecord = record.copy(
                stoppedAt = if (stopped) System.currentTimeMillis() else record.stoppedAt,
                lastKnownExitCode = if (stopped) exitCode else record.lastKnownExitCode
            )

            managedProcesses.remove(record.pid)

            val updatedProcesses = registry.processes.map {
                if (it.name == updatedRecord.name) updatedRecord else it
            }
            writeRegistry(ProcessRegistry(processes = updatedProcesses))

            return ProcessStopResult(
                name = updatedRecord.name,
                pid = updatedRecord.pid,
                stopped = stopped,
                wasRunning = wasRunning,
                force = request.force,
                exitCode = if (stopped) exitCode else null
            )
        }
    }

    override fun logs(request: ProcessLogsRequest): ProcessLogsResult {
        val targetPath = when {
            !request.path.isNullOrBlank() -> request.path
            else -> {
                val record = resolveRecord(request.name, request.pid)
                record.logPath
            }
        }

        val file = File(targetPath)
        val lines = if (file.exists()) tail(file, request.tailLines.coerceAtLeast(1)) else emptyList()
        val record = runCatching { resolveRecord(request.name, request.pid) }.getOrNull()

        return ProcessLogsResult(
            name = record?.name,
            pid = record?.pid,
            logPath = file.absolutePath,
            lines = lines
        )
    }

    override fun cleanup(): ProcessCleanupResult {
        synchronized(lock) {
            val registry = readRegistry()
            val (alive, orphaned) = registry.processes.partition { isRunning(it.pid) }
            writeRegistry(ProcessRegistry(processes = alive))
            orphaned.forEach { managedProcesses.remove(it.pid) }

            return ProcessCleanupResult(
                removed = orphaned.size,
                remaining = alive.size,
                removedNames = orphaned.map { it.name }.sorted()
            )
        }
    }

    private fun resolveRecord(name: String?, pid: Long?): StoredProcess {
        require(!(name.isNullOrBlank() && pid == null)) { "Provide either name or pid" }
        val registry = readRegistry()

        return when {
            !name.isNullOrBlank() -> registry.processes.firstOrNull { it.name == name }
            else -> registry.processes.firstOrNull { it.pid == pid }
        } ?: error("Process not found")
    }

    private fun buildCommandParts(request: ProcessStartRequest): List<String> {
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

    private fun renderCommand(request: ProcessStartRequest): String {
        val shell = request.shellCommand?.trim().orEmpty()
        if (shell.isNotBlank()) return shell
        return listOf(request.executable?.trim().orEmpty()).plus(request.args).joinToString(" ").trim()
    }

    private fun doHealthCheck(url: String, timeoutMs: Long): ProcessHealthResult {
        val started = System.currentTimeMillis()
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(1)))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            val elapsed = System.currentTimeMillis() - started
            ProcessHealthResult(
                url = url,
                healthy = response.statusCode() in 200..399,
                statusCode = response.statusCode(),
                responseTimeMs = elapsed,
                error = null
            )
        } catch (ex: Exception) {
            val elapsed = System.currentTimeMillis() - started
            ProcessHealthResult(
                url = url,
                healthy = false,
                statusCode = null,
                responseTimeMs = elapsed,
                error = ex.message
            )
        }
    }

    private fun isRunning(pid: Long): Boolean {
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private fun resolveExitCode(pid: Long, fallback: Int?): Int? {
        val tracked = managedProcesses[pid]
        if (tracked != null) {
            return runCatching { tracked.exitValue() }.getOrNull() ?: fallback
        }
        return fallback
    }

    private fun prepareLogFile(name: String, appendLogs: Boolean): File {
        val dir = File(logsDirectory)
        if (!dir.exists()) dir.mkdirs()
        val safeName = name.lowercase().replace(Regex("[^a-z0-9._-]"), "-")
        val logFile = File(dir, "$safeName.log")
        if (!appendLogs && logFile.exists()) {
            logFile.writeText("")
        }
        if (!logFile.exists()) logFile.createNewFile()
        return logFile
    }

    private fun readRegistry(): ProcessRegistry {
        val file = File(storePath)
        if (!file.exists()) {
            ensureStoreParent(file)
            return ProcessRegistry()
        }

        val content = file.readText(Charsets.UTF_8)
        if (content.isBlank()) return ProcessRegistry()

        return runCatching { mapper.readValue<ProcessRegistry>(content) }.getOrElse {
            ProcessRegistry()
        }
    }

    private fun writeRegistry(registry: ProcessRegistry) {
        val file = File(storePath)
        ensureStoreParent(file)
        val payload = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(registry)
        file.writeText(payload, Charsets.UTF_8)
    }

    private fun ensureStoreParent(file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
    }

    private fun tail(file: File, tailLines: Int): List<String> {
        val queue = ArrayDeque<String>(tailLines)
        file.bufferedReader().useLines { sequence ->
            sequence.forEach { line ->
                if (queue.size == tailLines) queue.removeFirst()
                queue.addLast(line)
            }
        }
        return queue.toList()
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
