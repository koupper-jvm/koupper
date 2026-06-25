package com.koupper.octopus.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.logging.GlobalLogger
import com.koupper.octopus.DaemonMetrics
import com.koupper.octopus.ScriptExecutor
import com.koupper.octopus.security.JwtAuth
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Lightweight HTTP REST API for Octopus daemon.
 *
 * Runs on port 9997 (one below the TCP command port 9998).
 * Uses JDK built-in HttpServer — zero external dependencies.
 *
 * Endpoints:
 *   POST /api/v1/run       → execute a script (JSON body)
 *   GET  /api/v1/health    → health check
 *   GET  /api/v1/status    → daemon metrics snapshot
 *   GET  /api/v1/jobs      → list queued jobs (file-based queues)
 *
 * Auth: Bearer JWT token in Authorization header (same JWT secret as TCP auth).
 * If no JWT secret is configured, requests are accepted without auth (dev mode).
 */
object HttpApiServer {

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private var server: HttpServer? = null

    data class RunRequest(
        val scriptPath: String,
        val context: String = ".",
        val params: Map<String, String> = emptyMap()
    )

    data class ApiResponse(
        val ok: Boolean,
        val result: String? = null,
        val error: String? = null,
        val traceId: String? = null
    )

    fun start(scriptExecutor: ScriptExecutor, port: Int = 9997) {
        if (server != null) {
            GlobalLogger.log.warn { "HTTP API server already running" }
            return
        }

        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        httpServer.executor = Executors.newFixedThreadPool(4)

        // POST /api/v1/run — execute script
        httpServer.createContext("/api/v1/run") { exchange ->
            handleCors(exchange)
            if (exchange.requestMethod != "POST") {
                sendJson(exchange, 405, ApiResponse(ok = false, error = "Method not allowed"))
                return@createContext
            }

            if (!authenticate(exchange)) {
                sendJson(exchange, 401, ApiResponse(ok = false, error = "Unauthorized"))
                return@createContext
            }

            try {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val req = mapper.readValue<RunRequest>(body)
                val traceId = java.util.UUID.randomUUID().toString().take(8)

                val paramString = req.params.entries.joinToString(" ") { (k, v) ->
                    val escaped = v.replace("\"", "\\\"")
                    if (v.contains(" ")) "$k=\"$escaped\"" else "$k=$escaped"
                }.ifBlank { "EMPTY_PARAMS" }

                var result: String? = null
                scriptExecutor.runFromScriptFile<Any?>(
                    context = req.context,
                    scriptPath = req.scriptPath,
                    params = paramString
                ) { output ->
                    result = when (output) {
                        is Unit, null -> ""
                        else -> output.toString()
                    }
                }

                sendJson(exchange, 200, ApiResponse(ok = true, result = result, traceId = traceId))
            } catch (e: Exception) {
                GlobalLogger.log.error(e) { "HTTP API /run failed" }
                sendJson(exchange, 500, ApiResponse(ok = false, error = e.message))
            }
        }

        // POST /api/v1/run-stream — execute script and stream output via SSE
        httpServer.createContext("/api/v1/run-stream") { exchange ->
            handleCors(exchange)
            if (exchange.requestMethod != "POST") {
                sendJson(exchange, 405, ApiResponse(ok = false, error = "Method not allowed"))
                return@createContext
            }

            if (!authenticate(exchange)) {
                sendJson(exchange, 401, ApiResponse(ok = false, error = "Unauthorized"))
                return@createContext
            }

            try {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val req = mapper.readValue<RunRequest>(body)
                val traceId = java.util.UUID.randomUUID().toString().take(8)

                val paramString = req.params.entries.joinToString(" ") { (k, v) ->
                    val escaped = v.replace("\"", "\\\"")
                    if (v.contains(" ")) "$k=\"$escaped\"" else "$k=$escaped"
                }.ifBlank { "EMPTY_PARAMS" }

                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.responseHeaders.add("Cache-Control", "no-cache")
                exchange.responseHeaders.add("Connection", "keep-alive")
                exchange.sendResponseHeaders(200, 0)
                
                val writer = exchange.responseBody.bufferedWriter()

                // Custom SessionOutput to bridge to SSE
                val sseWriter = java.io.BufferedWriter(object : java.io.Writer() {
                    private val sb = StringBuilder()
                    override fun write(cbuf: CharArray, off: Int, len: Int) {
                        sb.append(cbuf, off, len)
                    }
                    override fun flush() {
                        val str = sb.toString()
                        if (str.isNotBlank()) {
                            writer.write("data: $str\n")
                            writer.flush()
                        }
                        sb.clear()
                    }
                    override fun close() {}
                })
                
                val sseOutput = com.koupper.octopus.SessionOutput(sseWriter)

                com.koupper.octopus.SessionStdoutBridge.bind(sseOutput, com.koupper.octopus.ResponseMode.JSON)

                scriptExecutor.runFromScriptFile<Any?>(
                    context = req.context,
                    scriptPath = req.scriptPath,
                    params = paramString
                ) { finalResult ->
                    try {
                        val resultStr = when (finalResult) {
                            is Unit, null -> ""
                            else -> finalResult.toString()
                        }
                        val escaped = resultStr.replace("\n", "\\n").replace("\r", "")
                        writer.write("data: {\"event\": \"done\", \"result\": \"$escaped\"}\n\n")
                        writer.flush()
                    } catch (e: Exception) {}
                }

                com.koupper.octopus.SessionStdoutBridge.clear()
                writer.close()

            } catch (e: Exception) {
                GlobalLogger.log.error(e) { "HTTP API /run-stream failed" }
                // Cannot send JSON error if already started streaming, so just close
                exchange.close()
            }
        }

        // GET /api/v1/health — health check
        httpServer.createContext("/api/v1/health") { exchange ->
            handleCors(exchange)
            if (exchange.requestMethod != "GET") {
                sendJson(exchange, 405, ApiResponse(ok = false, error = "Method not allowed"))
                return@createContext
            }

            val snapshot = DaemonMetrics.snapshot()
            val health = mapOf(
                "status" to "ok",
                "uptimeMs" to snapshot.uptimeMs,
                "activeConnections" to snapshot.activeConnections,
                "totalScripts" to snapshot.totalScripts
            )
            sendJson(exchange, 200, mapper.writeValueAsString(health))
        }

        // GET /api/v1/status — daemon metrics
        httpServer.createContext("/api/v1/status") { exchange ->
            handleCors(exchange)
            if (exchange.requestMethod != "GET") {
                sendJson(exchange, 405, ApiResponse(ok = false, error = "Method not allowed"))
                return@createContext
            }

            if (!authenticate(exchange)) {
                sendJson(exchange, 401, ApiResponse(ok = false, error = "Unauthorized"))
                return@createContext
            }

            val snapshot = DaemonMetrics.snapshot()
            val status = mapOf(
                "ok" to true,
                "uptimeMs" to snapshot.uptimeMs,
                "activeConnections" to snapshot.activeConnections,
                "totalConnections" to snapshot.totalConnections,
                "totalCommands" to snapshot.totalCommands,
                "totalScripts" to snapshot.totalScripts,
                "successfulScripts" to snapshot.successfulScripts,
                "failedScripts" to snapshot.failedScripts,
                "unauthorizedCommands" to snapshot.unauthorizedCommands,
                "invalidCommands" to snapshot.invalidCommands
            )
            sendJson(exchange, 200, mapper.writeValueAsString(status))
        }

        // GET /api/v1/jobs — list queued jobs
        httpServer.createContext("/api/v1/jobs") { exchange ->
            handleCors(exchange)
            if (exchange.requestMethod != "GET") {
                sendJson(exchange, 405, ApiResponse(ok = false, error = "Method not allowed"))
                return@createContext
            }

            if (!authenticate(exchange)) {
                sendJson(exchange, 401, ApiResponse(ok = false, error = "Unauthorized"))
                return@createContext
            }

            try {
                val home = System.getProperty("user.home")
                val jobsDir = java.io.File(home, ".koupper/jobs")
                val queues = if (jobsDir.exists()) {
                    jobsDir.listFiles { it.isDirectory }?.map { queueDir ->
                        val jobs = queueDir.listFiles { it.extension == "json" }?.map { file ->
                            mapOf(
                                "id" to file.nameWithoutExtension,
                                "queue" to queueDir.name,
                                "size" to file.length()
                            )
                        } ?: emptyList()
                        mapOf("name" to queueDir.name, "jobs" to jobs, "count" to jobs.size)
                    } ?: emptyList()
                } else emptyList()

                sendJson(exchange, 200, mapper.writeValueAsString(mapOf("ok" to true, "queues" to queues)))
            } catch (e: Exception) {
                sendJson(exchange, 500, ApiResponse(ok = false, error = e.message))
            }
        }

        httpServer.start()
        server = httpServer
        GlobalLogger.log.info { "🌐 HTTP REST API available at http://127.0.0.1:$port/api/v1" }
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    // ── helpers ──

    private fun handleCors(exchange: HttpExchange) {
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
        if (exchange.requestMethod == "OPTIONS") {
            exchange.sendResponseHeaders(204, -1)
        }
    }

    private fun authenticate(exchange: HttpExchange): Boolean {
        // If JWT is not configured, allow all (dev mode)
        if (!JwtAuth.isEnabled()) return true

        val authHeader = exchange.requestHeaders.getFirst("Authorization") ?: return false
        if (!authHeader.startsWith("Bearer ")) return false
        val token = authHeader.removePrefix("Bearer ").trim()
        return JwtAuth.verifyToken(token) != null
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.close()
    }

    private fun sendJson(exchange: HttpExchange, status: Int, response: ApiResponse) {
        sendJson(exchange, status, mapper.writeValueAsString(response))
    }
}
