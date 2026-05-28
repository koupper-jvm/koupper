package com.koupper.providers.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class MCPToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?> = emptyMap()
)

data class MCPServerInfo(
    val host: String,
    val port: Int,
    val tools: List<MCPToolDescriptor>
)

interface MCPServerProvider {
    fun registerTool(
        name: String,
        description: String,
        inputSchema: Map<String, Any?> = emptyMap(),
        handler: (Map<String, Any?>) -> Any?
    )
    fun listTools(): List<MCPToolDescriptor>
    fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): Any?
    fun startHttp(host: String = "127.0.0.1", port: Int = 18082): MCPServerInfo
    fun stop()
}

// MCP-compliant HTTP server (JSON-RPC 2.0, protocol version 2024-11-05).
// Primary endpoint: POST /  — accepts all JSON-RPC 2.0 MCP messages.
// Legacy endpoints kept for backward compatibility:
//   GET  /mcp/tools  →  {"tools": [...]}
//   POST /mcp/call   →  {"name": "...", "arguments": {...}}
class LocalMCPServerProvider : MCPServerProvider {

    private val mapper   = jacksonObjectMapper()
    private val tools    = ConcurrentHashMap<String, Pair<MCPToolDescriptor, (Map<String, Any?>) -> Any?>>()
    private val executor = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
    private val running  = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    override fun registerTool(
        name: String,
        description: String,
        inputSchema: Map<String, Any?>,
        handler: (Map<String, Any?>) -> Any?
    ) {
        tools[name] = MCPToolDescriptor(name = name, description = description, inputSchema = inputSchema) to handler
    }

    override fun listTools(): List<MCPToolDescriptor> =
        tools.values.map { it.first }.sortedBy { it.name }

    override fun callTool(name: String, arguments: Map<String, Any?>): Any? {
        val tool = tools[name] ?: error("tool '$name' is not registered")
        return tool.second(arguments)
    }

    override fun startHttp(host: String, port: Int): MCPServerInfo {
        stop()
        running.set(true)
        val socket = ServerSocket(port, 50, InetAddress.getByName(host))
        serverSocket = socket
        serverThread = Thread {
            while (running.get() && !socket.isClosed) {
                runCatching {
                    val client = socket.accept()
                    executor.submit { handleConnection(client) }
                }
            }
        }.also { it.isDaemon = true; it.start() }
        return MCPServerInfo(host = host, port = port, tools = listTools())
    }

    override fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    // ── Connection handling ───────────────────────────────────────────────────

    private fun handleConnection(client: Socket) {
        client.use { sock ->
            runCatching {
                val input  = sock.getInputStream().bufferedReader()
                val output = sock.getOutputStream()

                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val httpMethod = parts[0].uppercase()
                val path       = parts[1].substringBefore("?")

                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:"))
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength); input.read(buf, 0, contentLength); String(buf)
                } else ""

                val (status, responseBody) = route(httpMethod, path, body)

                // Notifications produce an empty body — skip write
                if (responseBody.isEmpty()) return

                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 $status ${statusText(status)}\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(header.toByteArray())
                output.write(bytes)
                output.flush()
            }
        }
    }

    // ── Router ────────────────────────────────────────────────────────────────

    private fun route(httpMethod: String, path: String, body: String): Pair<Int, String> = when {
        // ── MCP JSON-RPC 2.0 (primary endpoint) ──────────────────────────────
        path == "/" && httpMethod == "POST" -> handleJsonRpc(body)

        // ── Legacy endpoints (backward compatibility) ─────────────────────────
        path == "/mcp/tools" && httpMethod == "GET" ->
            200 to mapper.writeValueAsString(mapOf("tools" to listTools()))

        path == "/mcp/call" && httpMethod == "POST" -> handleLegacyCall(body)

        path == "/mcp/tools" || path == "/mcp/call" ->
            405 to mapper.writeValueAsString(mapOf("error" to "Method not allowed"))

        else -> 404 to mapper.writeValueAsString(mapOf("error" to "Not found: $path"))
    }

    // ── JSON-RPC 2.0 dispatcher ───────────────────────────────────────────────

    private fun handleJsonRpc(body: String): Pair<Int, String> {
        val req = runCatching { mapper.readValue<Map<String, Any?>>(body) }
            .getOrElse { return 200 to rpcError(null, -32700, "Parse error") }

        if (req["jsonrpc"]?.toString() != "2.0")
            return 200 to rpcError(req["id"], -32600, "Invalid Request: jsonrpc must be '2.0'")

        val id     = req["id"]
        val method = req["method"]?.toString()
            ?: return 200 to rpcError(id, -32600, "Invalid Request: method is required")
        @Suppress("UNCHECKED_CAST")
        val params = req["params"] as? Map<String, Any?> ?: emptyMap()

        return when (method) {
            "initialize"               -> 200 to rpcOk(id, buildCapabilities())
            "notifications/initialized",
            "notifications/cancelled"  -> 200 to ""  // notifications — no response
            "ping"                     -> 200 to rpcOk(id, emptyMap<String, Any>())
            "tools/list"               -> 200 to rpcOk(id, buildToolList())
            "tools/call"               -> 200 to handleToolsCall(id, params)
            else                       -> 200 to rpcError(id, -32601, "Method not found: $method")
        }
    }

    // ── MCP method implementations ────────────────────────────────────────────

    private fun buildCapabilities() = mapOf(
        "protocolVersion" to MCP_VERSION,
        "capabilities"    to mapOf("tools" to emptyMap<String, Any>()),
        "serverInfo"      to mapOf("name" to "koupper-mcp", "version" to "1.0.0")
    )

    private fun buildToolList() = mapOf(
        "tools" to listTools().map { t ->
            mapOf(
                "name"        to t.name,
                "description" to t.description,
                "inputSchema" to t.inputSchema.ifEmpty { mapOf("type" to "object") }
            )
        }
    )

    private fun handleToolsCall(id: Any?, params: Map<String, Any?>): String {
        val name = params["name"]?.toString()
            ?: return rpcError(id, -32602, "Invalid params: 'name' is required")
        @Suppress("UNCHECKED_CAST")
        val arguments = params["arguments"] as? Map<String, Any?> ?: emptyMap()

        return try {
            val result = callTool(name, arguments)
            val text   = when (result) {
                is String -> result
                null      -> "null"
                else      -> mapper.writeValueAsString(result)
            }
            rpcOk(id, mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to text)),
                "isError" to false
            ))
        } catch (e: Exception) {
            rpcOk(id, mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to "Error: ${e.message}")),
                "isError" to true
            ))
        }
    }

    // ── Legacy call handler ───────────────────────────────────────────────────

    private fun handleLegacyCall(body: String): Pair<Int, String> {
        val payload = runCatching { mapper.readValue<Map<String, Any?>>(body) }
            .getOrElse { return 400 to mapper.writeValueAsString(mapOf("error" to "invalid JSON")) }
        val name = payload["name"]?.toString()
            ?: return 400 to mapper.writeValueAsString(mapOf("error" to "field 'name' is required"))
        @Suppress("UNCHECKED_CAST")
        val arguments = payload["arguments"] as? Map<String, Any?> ?: emptyMap()
        val result    = callTool(name, arguments)
        return 200 to mapper.writeValueAsString(mapOf("ok" to true, "name" to name, "result" to result))
    }

    // ── JSON-RPC response builders ────────────────────────────────────────────

    private fun rpcOk(id: Any?, result: Any): String =
        mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))

    private fun rpcError(id: Any?, code: Int, message: String): String =
        mapper.writeValueAsString(mapOf(
            "jsonrpc" to "2.0",
            "id"      to id,
            "error"   to mapOf("code" to code, "message" to message)
        ))

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 500 -> "Internal Server Error"
        else -> "Unknown"
    }

    companion object {
        const val MCP_VERSION = "2024-11-05"
    }
}
