package com.koupper.providers.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Configuration for an external MCP server.
// transport = "http"  → connects to a running HTTP server at [url]
// transport = "stdio" → spawns [command] + [args] as a subprocess, speaks JSON-RPC over stdin/stdout
data class MCPServerConfig(
    val name: String,
    val transport: String = "http",
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
)

// A connected server with its discovered tools.
// Tools are prefixed: "playwright.screenshot", "github.create_pr", etc.
data class MCPConnectedServer(
    val config: MCPServerConfig,
    val tools: List<MCPToolDescriptor>
)

interface MCPClientProvider {
    // Connect to an external MCP server and discover its tools.
    fun connect(config: MCPServerConfig): MCPConnectedServer

    // Call a tool on a specific connected server.
    fun callTool(server: MCPConnectedServer, toolName: String, arguments: Map<String, Any?> = emptyMap()): Any?

    // Disconnect and clean up resources (kill stdio subprocess if any).
    fun disconnect(server: MCPConnectedServer)
}

class LocalMCPClientProvider : MCPClientProvider {

    private val mapper     = jacksonObjectMapper()
    private val http       = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val idCounter  = AtomicInteger(1)
    private val processes  = ConcurrentHashMap<String, Process>()          // stdio subprocesses
    private val readers    = ConcurrentHashMap<String, BufferedReader>()
    private val writers    = ConcurrentHashMap<String, PrintWriter>()

    // ── Connect ───────────────────────────────────────────────────────────────

    override fun connect(config: MCPServerConfig): MCPConnectedServer {
        return when (config.transport) {
            "http"  -> connectHttp(config)
            "stdio" -> connectStdio(config)
            else    -> error("Unknown MCP transport: ${config.transport}")
        }
    }

    private fun connectHttp(config: MCPServerConfig): MCPConnectedServer {
        val url = requireNotNull(config.url) { "url is required for http transport" }

        // MCP initialize handshake
        sendJsonRpc(url, "initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities"    to emptyMap<String, Any>(),
            "clientInfo"      to mapOf("name" to "koupper-mcp-client", "version" to "1.0")
        ))
        // notifications/initialized (no response expected)
        sendJsonRpcNotification(url, "notifications/initialized")

        val tools = listToolsHttp(url)
        return MCPConnectedServer(config, tools)
    }

    private fun connectStdio(config: MCPServerConfig): MCPConnectedServer {
        val cmd = requireNotNull(config.command) { "command is required for stdio transport" }
        val key = config.name

        val pb = ProcessBuilder(listOf(cmd) + config.args)
            .also { pb ->
                pb.environment().putAll(config.env)
                pb.redirectErrorStream(false)
            }
        val proc = pb.start()
        processes[key] = proc

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val writer = PrintWriter(proc.outputStream, true)
        readers[key] = reader
        writers[key] = writer

        // Initialize handshake
        writeStdio(key, "initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities"    to emptyMap<String, Any>(),
            "clientInfo"      to mapOf("name" to "koupper-mcp-client", "version" to "1.0")
        ))
        readStdioResponse(key)  // consume initialize response

        // notifications/initialized
        val notif = mapper.writeValueAsString(mapOf(
            "jsonrpc" to "2.0",
            "method"  to "notifications/initialized",
            "params"  to emptyMap<String, Any>()
        ))
        writers[key]?.println(notif)

        val tools = listToolsStdio(key)
        return MCPConnectedServer(config, tools)
    }

    // ── List tools ────────────────────────────────────────────────────────────

    private fun listToolsHttp(url: String): List<MCPToolDescriptor> = runCatching {
        val resp = sendJsonRpc(url, "tools/list", emptyMap())
        val tools = mapper.readTree(resp).get("result")?.get("tools") ?: return emptyList()
        tools.map { t ->
            MCPToolDescriptor(
                name        = t.get("name")?.asText() ?: "",
                description = t.get("description")?.asText() ?: "",
                inputSchema = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    mapper.convertValue(t.get("inputSchema"), Map::class.java) as Map<String, Any?>
                }.getOrDefault(emptyMap())
            )
        }
    }.getOrDefault(emptyList())

    private fun listToolsStdio(key: String): List<MCPToolDescriptor> = runCatching {
        writeStdio(key, "tools/list", emptyMap())
        val resp = readStdioResponse(key) ?: return emptyList()
        val tools = mapper.readTree(resp).get("result")?.get("tools") ?: return emptyList()
        tools.map { t ->
            MCPToolDescriptor(
                name        = t.get("name")?.asText() ?: "",
                description = t.get("description")?.asText() ?: "",
                inputSchema = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    mapper.convertValue(t.get("inputSchema"), Map::class.java) as Map<String, Any?>
                }.getOrDefault(emptyMap())
            )
        }
    }.getOrDefault(emptyList())

    // ── Call tool ─────────────────────────────────────────────────────────────

    override fun callTool(server: MCPConnectedServer, toolName: String, arguments: Map<String, Any?>): Any? {
        val params = mapOf("name" to toolName, "arguments" to arguments)
        return when (server.config.transport) {
            "http" -> {
                val url  = requireNotNull(server.config.url)
                val resp = sendJsonRpc(url, "tools/call", params)
                val tree = mapper.readTree(resp)
                val content = tree.get("result")?.get("content")
                content?.get(0)?.get("text")?.asText()
                    ?: tree.get("result")?.toString()
                    ?: "no result"
            }
            "stdio" -> {
                val key = server.config.name
                writeStdio(key, "tools/call", params)
                val resp = readStdioResponse(key) ?: return "no response"
                val tree = mapper.readTree(resp)
                tree.get("result")?.get("content")?.get(0)?.get("text")?.asText()
                    ?: tree.get("result")?.toString()
                    ?: "no result"
            }
            else -> error("Unknown transport: ${server.config.transport}")
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    override fun disconnect(server: MCPConnectedServer) {
        val key = server.config.name
        runCatching { writers[key]?.close() }
        runCatching { readers[key]?.close() }
        runCatching { processes[key]?.destroy() }
        processes.remove(key); readers.remove(key); writers.remove(key)
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun sendJsonRpc(url: String, method: String, params: Map<String, Any?>): String {
        val id      = idCounter.getAndIncrement()
        val payload = mapper.writeValueAsString(mapOf(
            "jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params
        ))
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun sendJsonRpcNotification(url: String, method: String) {
        val payload = mapper.writeValueAsString(mapOf(
            "jsonrpc" to "2.0", "method" to method, "params" to emptyMap<String, Any>()
        ))
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        runCatching { http.send(req, HttpResponse.BodyHandlers.ofString()) }
    }

    // ── Stdio helpers ─────────────────────────────────────────────────────────

    private fun writeStdio(key: String, method: String, params: Map<String, Any?>) {
        val id      = idCounter.getAndIncrement()
        val payload = mapper.writeValueAsString(mapOf(
            "jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params
        ))
        writers[key]?.println(payload)
    }

    private fun readStdioResponse(key: String): String? {
        val reader = readers[key] ?: return null
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            if (line.isNotBlank()) return line
        }
        return null
    }
}
