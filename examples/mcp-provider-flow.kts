/**
 * MCP Provider Demo
 *
 * Purpose:
 * - Register tools and validate local HTTP MCP-style endpoints.
 */
import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.mcp.MCPServerProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class Input(
    val port: Int = 18082
)

@Export
val mcpDemo: (Input) -> Map<String, Any?> = { input ->
    val mcp = app.getInstance(MCPServerProvider::class)

    mcp.registerTool(
        name = "sum",
        description = "Adds two numbers",
        inputSchema = mapOf("a" to "number", "b" to "number")
    ) { args ->
        val a = (args["a"] as? Number)?.toDouble() ?: 0.0
        val b = (args["b"] as? Number)?.toDouble() ?: 0.0
        mapOf("value" to (a + b))
    }

    val server = mcp.startHttp(port = input.port)

    val client = HttpClient.newHttpClient()
    val listRequest = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:${input.port}/mcp/tools"))
        .GET()
        .build()
    val listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString())

    val callRequest = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:${input.port}/mcp/call"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"sum\",\"arguments\":{\"a\":2,\"b\":5}}"))
        .build()
    val callResponse = client.send(callRequest, HttpResponse.BodyHandlers.ofString())

    mcp.stop()

    mapOf(
        "ok" to (listResponse.statusCode() == 200 && callResponse.statusCode() == 200),
        "server" to server,
        "toolsStatus" to listResponse.statusCode(),
        "toolsPayload" to listResponse.body(),
        "callStatus" to callResponse.statusCode(),
        "callPayload" to callResponse.body()
    )
}
