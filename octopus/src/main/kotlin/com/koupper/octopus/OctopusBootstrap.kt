package com.koupper.octopus

import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.logging.*
import com.koupper.logging.GlobalLogger
import com.koupper.octopus.process.Process
import com.koupper.orchestrator.ScriptRunner
import com.koupper.os.env
import com.koupper.providers.http.HtppClient
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.toType
import com.koupper.providers.io.TerminalContext
import com.koupper.providers.io.TerminalIO
import com.koupper.shared.monitoring.JsonlExecutionMonitor
import kotlinx.coroutines.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

fun main() = runBlocking {
    SessionStdoutBridge.installOnce()

    val processManager = createDefaultConfiguration()

    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    serverScope.launch {
        listenForExternalCommands(processManager, serverScope)
    }

    // Start Prometheus metrics endpoint on a background thread
    Thread {
        startPrometheusMetricsServer()
    }.apply { isDaemon = true; start() }

    // Start HTTP REST API on a background thread
    Thread {
        com.koupper.octopus.api.HttpApiServer.start(processManager)
    }.apply { isDaemon = true; start() }

    while (true) delay(1000)
}


fun listenForExternalCommands(
    processManager: ScriptExecutor, scope: CoroutineScope, maxConnections: Int? = null
) {
    val host = runtimeOctopusHost()
    val port = runtimeOctopusPort()
    val serverSocket = ServerSocket(port, 50, InetAddress.getByName(host))
    val authEnabled = !runtimeOctopusToken().isNullOrBlank()
    app.createSingleton(LoggerCore::class)
        .info { "\uD83D\uDD04 Octopus listening on $host:$port (auth=${if (authEnabled) "enabled" else "disabled"})" }

    var acceptedConnections = 0

    try {
        while (maxConnections == null || acceptedConnections < maxConnections) {
            val clientSocket = serverSocket.accept()
            acceptedConnections++
            DaemonMetrics.onConnectionAccepted()
            app.createSingleton(LoggerCore::class)
                .info { "\uD83D\uDD17 New connection to Octopus: ${clientSocket.inetAddress.hostAddress}" }

            scope.launch(Dispatchers.IO) {
                clientSocket.use {
                    try {
                        val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        val writer = it.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        val sessionOutput = SessionOutput(writer)
                        val sessionId = java.util.UUID.randomUUID().toString().take(8)
                        val sessionLogger = app.createSingleton(LoggerCore::class)
                        TerminalContext.set(TerminalIO(reader, writer))
                        SessionStdoutBridge.bind(sessionOutput, ResponseMode.LEGACY, null)
                        val firstLine = reader.readLine()?.trim()

                        if (firstLine.isNullOrBlank() || firstLine == "null") return@launch

                        val requiredToken = runtimeOctopusToken()
                        val (authenticated, command) = parseAuthenticatedCommand(firstLine, reader, requiredToken)

                        if (!authenticated) {
                            DaemonMetrics.onUnauthorizedCommand()
                            sessionLogger.warn { "\u26A0\uFE0F [session=$sessionId] Unauthorized command rejected." }
                            sessionLogger.warn {
                                structuredEvent(
                                    event = "octopus.auth.rejected", fields = mapOf(
                                        "sessionId" to sessionId, "remoteAddress" to it.inetAddress.hostAddress
                                    )
                                )
                            }
                            sessionOutput.error("Unauthorized: invalid or missing token")
                            return@launch
                        }

                        if (command.isNullOrBlank() || command == "null") return@launch

                        // Extract token for scope validation (JWT mode only)
                        val providedToken = if (firstLine.startsWith("AUTH::")) {
                            firstLine.removePrefix("AUTH::").trim()
                        } else null

                        DaemonMetrics.onCommandReceived()

                        sessionLogger.info { "\uD83D\uDCE5 [session=$sessionId] Command received in Octopus: $command" }

                        val parsedCommand = parseIncomingCommand(command)
                        if (parsedCommand == null) {
                            DaemonMetrics.onInvalidCommand()
                            sessionLogger.warn {
                                structuredEvent(
                                    event = "octopus.command.invalid", fields = mapOf(
                                        "sessionId" to sessionId, "payload" to command
                                    )
                                )
                            }
                            sessionOutput.error("Invalid command format")
                            return@launch
                        }

                        // Scope validation for JWT tokens
                        if (providedToken != null && !validateCommandScope(providedToken, parsedCommand.commandType)) {
                            DaemonMetrics.onUnauthorizedCommand()
                            sessionLogger.warn {
                                structuredEvent(
                                    event = "octopus.auth.forbidden", fields = mapOf(
                                        "sessionId" to sessionId,
                                        "commandType" to parsedCommand.commandType,
                                        "remoteAddress" to it.inetAddress.hostAddress
                                    )
                                )
                            }
                            sessionOutput.error("Forbidden: insufficient scope for command '${parsedCommand.commandType}'")
                            return@launch
                        }

                        SessionStdoutBridge.bind(sessionOutput, parsedCommand.mode, parsedCommand.requestId)

                        val traceId = parsedCommand.requestId?.takeIf { it.isNotBlank() } ?: TraceContext.generate()
                        TraceContext.set(traceId)

                        when {
                            parsedCommand.commandType == "CANCEL" -> {
                                val cancelled = ActiveExecutions.cancel(parsedCommand.requestId)
                                sessionOutput.result(
                                    "{\"ok\":${if (cancelled) "true" else "false"},\"requestId\":\"${parsedCommand.requestId ?: ""}\",\"cancelled\":${if (cancelled) "true" else "false"}}",
                                    parsedCommand.mode,
                                    parsedCommand.requestId
                                )
                            }

                            parsedCommand.commandType == "UPDATING_CHECK" -> {
                                checkForUpdates()
                            }

                            parsedCommand.commandType == "RELOAD_PROVIDERS" -> {
                                val spm = com.koupper.providers.ServiceProviderManager()
                                spm.reloadProvidersFromDirectory(System.getProperty("user.home") + "/.koupper/providers")

                                // Clear container and re-register
                                app.clear()

                                // Re-initialize Octopus config
                                val octopus = com.koupper.octopus.Octopus(app)
                                octopus.registerBuildInServicesProvidersInContainer()

                                sessionOutput.result(
                                    "{\"ok\":true,\"requestId\":\"${parsedCommand.requestId ?: ""}\",\"reloaded\":true}",
                                    parsedCommand.mode,
                                    parsedCommand.requestId
                                )
                            }

                            parsedCommand.commandType == "HEALTH_CHECK" -> {
                                val snapshot = DaemonMetrics.snapshot()
                                val health = "{" + listOf(
                                    jsonField("status", "ok"),
                                    jsonField("uptimeMs", snapshot.uptimeMs.toString()),
                                    jsonField("activeConnections", snapshot.activeConnections.toString()),
                                    jsonField("totalConnections", snapshot.totalConnections.toString()),
                                    jsonField("totalCommands", snapshot.totalCommands.toString()),
                                    jsonField("totalScripts", snapshot.totalScripts.toString()),
                                    jsonField("successfulScripts", snapshot.successfulScripts.toString()),
                                    jsonField("failedScripts", snapshot.failedScripts.toString()),
                                    jsonField("unauthorizedCommands", snapshot.unauthorizedCommands.toString()),
                                    jsonField("invalidCommands", snapshot.invalidCommands.toString())
                                ).joinToString(",") + "}"

                                sessionLogger.info {
                                    structuredEvent(
                                        event = "octopus.health.request", fields = mapOf(
                                            "sessionId" to sessionId,
                                            "requestId" to parsedCommand.requestId,
                                            "mode" to parsedCommand.mode.name.lowercase()
                                        )
                                    )
                                }

                                sessionOutput.result(health, parsedCommand.mode, parsedCommand.requestId)
                            }

                            parsedCommand.commandType == "DEPLOY" -> {
                                if (requiredToken.isNullOrBlank()) {
                                    sessionOutput.error(
                                        "DEPLOY requires daemon auth token configuration",
                                        parsedCommand.mode,
                                        parsedCommand.requestId
                                    )
                                    return@launch
                                }

                                val deployContent = parsedCommand.scriptContent
                                if (deployContent.isNullOrBlank()) {
                                    sessionOutput.error(
                                        "DEPLOY payload is missing scriptContent",
                                        parsedCommand.mode,
                                        parsedCommand.requestId
                                    )
                                    return@launch
                                }

                                val deployBytes = deployContent.toByteArray(Charsets.UTF_8)
                                val maxDeployBytes = runtimeDeployMaxBytes()
                                if (deployBytes.size > maxDeployBytes) {
                                    sessionOutput.error(
                                        "DEPLOY payload exceeds max size ($maxDeployBytes bytes)",
                                        parsedCommand.mode,
                                        parsedCommand.requestId
                                    )
                                    return@launch
                                }

                                val providedSha256 = parsedCommand.contentSha256?.lowercase()
                                if (providedSha256.isNullOrBlank()) {
                                    sessionOutput.error(
                                        "DEPLOY payload is missing contentSha256",
                                        parsedCommand.mode,
                                        parsedCommand.requestId
                                    )
                                    return@launch
                                }

                                val calculatedSha256 = sha256Hex(deployBytes)
                                if (providedSha256 != calculatedSha256) {
                                    sessionOutput.error(
                                        "DEPLOY payload hash mismatch", parsedCommand.mode, parsedCommand.requestId
                                    )
                                    return@launch
                                }

                                val deployDir = File(System.getProperty("user.home"), ".koupper/deployed")
                                deployDir.mkdirs()
                                val targetFileName =
                                    parsedCommand.scriptPath.substringAfterLast("/").substringAfterLast("\\")
                                        .ifBlank { "deployed.kts" }
                                if (!deployScriptNameRegex.matches(targetFileName)) {
                                    sessionOutput.error(
                                        "DEPLOY script name must be a safe .kts/.kt filename",
                                        parsedCommand.mode,
                                        parsedCommand.requestId
                                    )
                                    return@launch
                                }
                                val targetFile = File(deployDir, targetFileName)
                                targetFile.writeBytes(deployBytes)

                                DaemonMetrics.onScriptStarted()
                                val deployStartedAt = System.nanoTime()

                                sessionLogger.info {
                                    "\uD83D\uDCE6 [session=$sessionId] Deploying script: $targetFileName params=${parsedCommand.params}"
                                }

                                try {
                                    processManager.runFromScriptFile(
                                        deployDir.absolutePath, targetFile.name, parsedCommand.params
                                    ) { result: Any ->
                                        System.out.flush()
                                        val out = if (result is Unit) "" else result.toString()
                                        DaemonMetrics.onScriptSucceeded()
                                        val durationMs = (System.nanoTime() - deployStartedAt) / 1_000_000
                                        sessionLogger.info {
                                            structuredEvent(
                                                event = "octopus.deploy.completed", fields = mapOf(
                                                    "sessionId" to sessionId,
                                                    "requestId" to parsedCommand.requestId,
                                                    "script" to targetFileName,
                                                    "sizeBytes" to deployBytes.size,
                                                    "sha256" to calculatedSha256,
                                                    "durationMs" to durationMs,
                                                    "status" to "ok"
                                                )
                                            )
                                        }
                                        sessionOutput.result(out, parsedCommand.mode, parsedCommand.requestId)
                                    }
                                } catch (e: Exception) {
                                    DaemonMetrics.onScriptFailed()
                                    val durationMs = (System.nanoTime() - deployStartedAt) / 1_000_000
                                    sessionLogger.error {
                                        structuredEvent(
                                            event = "octopus.deploy.failed", fields = mapOf(
                                                "sessionId" to sessionId,
                                                "requestId" to parsedCommand.requestId,
                                                "script" to targetFileName,
                                                "sizeBytes" to deployBytes.size,
                                                "sha256" to calculatedSha256,
                                                "durationMs" to durationMs,
                                                "status" to "error",
                                                "error" to (e.message ?: e.javaClass.name)
                                            )
                                        )
                                    }
                                    sessionOutput.error(
                                        e.message ?: "Deploy execution failed",
                                        parsedCommand.mode,
                                        parsedCommand.requestId
                                    )
                                } finally {
                                    System.out.flush()
                                    it.shutdownOutput()
                                }
                            }

                            parsedCommand.scriptPath.endsWith(".kts") || parsedCommand.scriptPath.endsWith(".kt") -> {
                                DaemonMetrics.onScriptStarted()
                                val startedAt = System.nanoTime()
                                val requestId = parsedCommand.requestId
                                if (!requestId.isNullOrBlank()) {
                                    ActiveExecutions.register(requestId, parsedCommand.scriptPath)
                                }

                                try {
                                    sessionLogger.info {
                                        "\uD83D\uDCDC [session=$sessionId] Executing script: ${parsedCommand.scriptPath} with params: ${parsedCommand.params}"
                                    }

                                    processManager.runFromScriptFile(
                                        parsedCommand.context, parsedCommand.scriptPath, parsedCommand.params
                                    ) { result: Any ->
                                        System.out.flush()

                                        val out = when (result) {
                                            is Unit -> ""
                                            else -> result.toString()
                                        }

                                        DaemonMetrics.onScriptSucceeded()
                                        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                                        sessionLogger.info {
                                            structuredEvent(
                                                event = "octopus.script.completed", fields = mapOf(
                                                    "sessionId" to sessionId,
                                                    "requestId" to parsedCommand.requestId,
                                                    "script" to parsedCommand.scriptPath,
                                                    "durationMs" to durationMs,
                                                    "status" to "ok"
                                                )
                                            )
                                        }

                                        sessionOutput.result(out, parsedCommand.mode, parsedCommand.requestId)
                                    }
                                } catch (e: Exception) {
                                    DaemonMetrics.onScriptFailed()
                                    val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                                    sessionLogger.error {
                                        structuredEvent(
                                            event = "octopus.script.failed", fields = mapOf(
                                                "sessionId" to sessionId,
                                                "requestId" to parsedCommand.requestId,
                                                "script" to parsedCommand.scriptPath,
                                                "durationMs" to durationMs,
                                                "status" to "error",
                                                "error" to (e.message ?: e.javaClass.name)
                                            )
                                        )
                                    }
                                    sessionOutput.error(
                                        e.message ?: "Script execution failed",
                                        parsedCommand.mode,
                                        parsedCommand.requestId
                                    )
                                } finally {
                                    ActiveExecutions.unregister(requestId)
                                    System.out.flush()
                                    it.shutdownOutput()
                                }
                            }

                            else -> {
                                DaemonMetrics.onInvalidCommand()
                                app.createSingleton(LoggerCore::class)
                                    .info { "\u26A0\uFE0F  Invalid command format: $command" }
                            }
                        }
                    } catch (e: Exception) {
                        val traceMessage = e.message ?: e.toString()
                        app.createSingleton(LoggerCore::class).error { "\u26A0\uFE0F Error: $traceMessage" }
                        try {
                            val writer = it.getOutputStream().bufferedWriter(Charsets.UTF_8)
                            SessionOutput(writer).error(traceMessage, ResponseMode.LEGACY, null)
                        } catch (_: Exception) {
                        }
                    } finally {
                        TraceContext.clear()
                        SessionStdoutBridge.clear()
                        TerminalContext.clear()
                        DaemonMetrics.onConnectionClosed()
                    }
                }
            }
        }
    } finally {
        runCatching { serverSocket.close() }
    }
}

fun checkForUpdates(): Boolean {
    val checkForUpdateUrl = env("CHECK_FOR_UPDATED_URL")

    val httpClient = app.getInstance(HtppClient::class)

    val response = httpClient.get {
        url = checkForUpdateUrl
    }

    data class Versioning(val statusCode: String, val body: String)

    val textJsonParser = app.getInstance(JSONFileHandler::class)

    textJsonParser.read(response?.asString()!!)

    val versioning: Versioning = textJsonParser.toType()

    textJsonParser.read(versioning.body)

    data class Project(val name: String, val version: String)

    data class Info(val apps: ArrayList<Project>)

    val info: Info = textJsonParser.toType()

    info.apps.forEach { project ->
        if ((project.name == "octopus" && project.version != env("OCTOPUS_VERSION")) || (project.name == "koupper-installer" && project.version != env(
                "KOUPPER_CLI_VERSION"
            ))
        ) {
            print("AVAILABLE_UPDATES")

            exitProcess(0)
        }
    }

    return false
}

private fun processCallback(context: ScriptExecutor, scriptName: String, result: Any) {
    if (isPrimitiveType(result) || result is String) {
        val logFile = File(System.getProperty("java.io.tmpdir"), "octopus.log")

        logFile.writeText(
            when (result) {
                is String -> result
                is Process -> "\n\rModule ${ANSI_GREEN_155}${result.processName()}$ANSI_RESET created.\u001B[0m\n"
                else -> result.toString()
            }
        )
    } else if (result is Process) {
        GlobalLogger.log.info { "Module ${result.processName()} created." }
    }
}

fun isPrimitiveType(value: Any): Boolean {
    return when (value) {
        is Int, is Double, is Float, is Long, is Short, is Byte, is Boolean, is Char -> true
        else -> false
    }
}

fun createDefaultConfiguration(container: Container = app): ScriptExecutor {
    val octopus = Octopus(container)
    octopus.registerBuildInServicesProvidersInContainer()

    val logsDir = File(System.getProperty("user.home"), ".koupper/logs")

    val appLogger = LoggerFactory.get("Octopus.Main")
    appLogger.clearAppenders(close = true)
    appLogger.level = LogLevel.INFO

    appLogger.addAppender(
        AsyncAppender(
            RollingFileAppender(
                dir = logsDir, baseName = "octopus-system"
            )
        )
    )

    app.singleton(LoggerCore::class, { appLogger })
    app.bind(ScriptExecutor::class, { octopus })
    app.bind(com.koupper.shared.monitoring.ExecutionMonitor::class, {
        com.koupper.octopus.monitoring.CompositeExecutionMonitor(
            delegates = listOf(
                JsonlExecutionMonitor(File(logsDir, "octopus-executions.jsonl")),
                com.koupper.octopus.monitoring.ResumenArchivosExecutionMonitor(),
                com.koupper.octopus.monitoring.ObservabilityExecutionMonitor()
            )
        )
    })
    ScriptRunner.monitor = app.getInstance(com.koupper.shared.monitoring.ExecutionMonitor::class)

    return octopus
}

fun createLambdaConfiguration(container: Container = app): ScriptExecutor {
    val octopus = Octopus(container)
    octopus.registerBuildInServicesProvidersInContainer()

    val appLogger = LoggerFactory.get("Octopus.Main")
    appLogger.clearAppenders(close = true)
    appLogger.level = LogLevel.INFO

    app.singleton(LoggerCore::class, { appLogger })
    app.bind(ScriptExecutor::class, { octopus })

    app.bind(com.koupper.shared.monitoring.ExecutionMonitor::class, {
        com.koupper.octopus.monitoring.CompositeExecutionMonitor(
            delegates = listOf(
                com.koupper.octopus.monitoring.ObservabilityExecutionMonitor()
            )
        )
    })
    ScriptRunner.monitor = app.getInstance(com.koupper.shared.monitoring.ExecutionMonitor::class)

    return octopus
}

// ──────────────────────────────────────────────
// Prometheus metrics endpoint
// ──────────────────────────────────────────────

fun startPrometheusMetricsServer(port: Int = 9999) {
    val httpServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", port), 0)
    httpServer.createContext("/metrics") { exchange ->
        val snapshot = DaemonMetrics.snapshot()
        val body = buildString {
            appendLine("# HELP koupper_uptime_ms Daemon uptime in milliseconds")
            appendLine("# TYPE koupper_uptime_ms gauge")
            appendLine("koupper_uptime_ms ${snapshot.uptimeMs}")
            appendLine()
            appendLine("# HELP koupper_active_connections Current active TCP connections")
            appendLine("# TYPE koupper_active_connections gauge")
            appendLine("koupper_active_connections ${snapshot.activeConnections}")
            appendLine()
            appendLine("# HELP koupper_total_commands_total Total commands processed")
            appendLine("# TYPE koupper_total_commands_total counter")
            appendLine("koupper_total_commands_total ${snapshot.totalCommands}")
            appendLine()
            appendLine("# HELP koupper_total_scripts_total Total scripts executed")
            appendLine("# TYPE koupper_total_scripts_total counter")
            appendLine("koupper_total_scripts_total ${snapshot.totalScripts}")
            appendLine()
            appendLine("# HELP koupper_successful_scripts_total Successful script executions")
            appendLine("# TYPE koupper_successful_scripts_total counter")
            appendLine("koupper_successful_scripts_total ${snapshot.successfulScripts}")
            appendLine()
            appendLine("# HELP koupper_failed_scripts_total Failed script executions")
            appendLine("# TYPE koupper_failed_scripts_total counter")
            appendLine("koupper_failed_scripts_total ${snapshot.failedScripts}")
            appendLine()
            appendLine("# HELP koupper_unauthorized_commands_total Rejected unauthorized commands")
            appendLine("# TYPE koupper_unauthorized_commands_total counter")
            appendLine("koupper_unauthorized_commands_total ${snapshot.unauthorizedCommands}")
            appendLine()
            appendLine("# HELP koupper_invalid_commands_total Rejected invalid commands")
            appendLine("# TYPE koupper_invalid_commands_total counter")
            appendLine("koupper_invalid_commands_total ${snapshot.invalidCommands}")
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.close()
    }
    httpServer.start()
    GlobalLogger.log.info { "📊 Prometheus metrics available at http://127.0.0.1:$port/metrics" }
}
