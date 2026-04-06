package com.koupper.providers.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

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
    private var server: HttpServer? = null
    private var host: String = "127.0.0.1"
    private var port: Int = 18082

    override fun registerTool(
        name: String,
        description: String,
        inputSchema: Map<String, Any?>,
        handler: (Map<String, Any?>) -> Any?
    ) {
        tools[name] = MCPToolDescriptor(name = name, description = description, inputSchema = inputSchema) to handler
    }

    override fun listTools(): List<MCPToolDescriptor> {
        return tools.values.map { it.first }.sortedBy { it.name }
    }

    override fun callTool(name: String, arguments: Map<String, Any?>): Any? {
        val tool = tools[name] ?: error("tool '$name' is not registered")
        return tool.second(arguments)
    }

    override fun startHttp(host: String, port: Int): MCPServerInfo {
        stop()
        this.host = host
        this.port = port

        val running = HttpServer.create(InetSocketAddress(host, port), 0)

        running.createContext("/mcp/tools") { exchange ->
            if (exchange.requestMethod.uppercase() != "GET") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return@createContext
            }
            respond(exchange, 200, mapOf("tools" to listTools()))
        }

        running.createContext("/mcp/call") { exchange ->
            if (exchange.requestMethod.uppercase() != "POST") {
                respond(exchange, 405, mapOf("error" to "Method not allowed"))
                return@createContext
            }

            try {
                val payload = mapper.readValue<Map<String, Any?>>(exchange.requestBody.bufferedReader().readText())
                val name = payload["name"]?.toString() ?: error("field 'name' is required")
                @Suppress("UNCHECKED_CAST")
                val arguments = payload["arguments"] as? Map<String, Any?> ?: emptyMap()
                val result = callTool(name, arguments)
                respond(exchange, 200, mapOf("ok" to true, "name" to name, "result" to result))
            } catch (error: Throwable) {
                respond(exchange, 500, mapOf("ok" to false, "error" to (error.message ?: "tool call failed")))
            }
        }

        running.start()
        server = running
        return MCPServerInfo(host = host, port = port, tools = listTools())
    }

    override fun stop() {
        server?.stop(0)
        server = null
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, payload: Any) {
        val bytes = mapper.writeValueAsBytes(payload)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
