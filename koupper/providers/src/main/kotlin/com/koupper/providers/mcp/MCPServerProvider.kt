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

class LocalMCPServerProvider : MCPServerProvider {
    private val mapper = jacksonObjectMapper()
    private val tools = ConcurrentHashMap<String, Pair<MCPToolDescriptor, (Map<String, Any?>) -> Any?>>()
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
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
                try {
                    val client = socket.accept()
                    executor.submit { handleConnection(client) }
                } catch (_: Exception) {
                    // socket closed or server stopped
                }
            }
        }.also { it.isDaemon = true; it.start() }
        return MCPServerInfo(host = host, port = port, tools = listTools())
    }

    override fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    private fun handleConnection(client: Socket) {
        client.use { sock ->
            try {
                val input = sock.getInputStream().bufferedReader()
                val output = sock.getOutputStream()

                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore("?")

                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0 && line.substring(0, colonIdx).trim().lowercase() == "content-length") {
                        contentLength = line.substring(colonIdx + 1).trim().toIntOrNull() ?: 0
                    }
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    input.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                val (status, responseBody) = route(method, path, body)
                writeResponse(output, status, responseBody)
            } catch (_: Exception) {
                // connection dropped or malformed request
            }
        }
    }

    private fun route(method: String, path: String, body: String): Pair<Int, String> {
        return when {
            path == "/mcp/tools" && method == "GET" ->
                200 to mapper.writeValueAsString(mapOf("tools" to listTools()))

            path == "/mcp/call" && method == "POST" -> {
                val payload = mapper.readValue<Map<String, Any?>>(body)
                val name = payload["name"]?.toString() ?: error("field 'name' is required")
                @Suppress("UNCHECKED_CAST")
                val arguments = payload["arguments"] as? Map<String, Any?> ?: emptyMap()
                val result = callTool(name, arguments)
                200 to mapper.writeValueAsString(mapOf("ok" to true, "name" to name, "result" to result))
            }

            path == "/mcp/tools" || path == "/mcp/call" ->
                405 to mapper.writeValueAsString(mapOf("error" to "Method not allowed"))

            else ->
                404 to mapper.writeValueAsString(mapOf("error" to "Not found: $path"))
        }
    }

    private fun writeResponse(output: java.io.OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }
}
