/**
 * Runtime Router Provider Demo
 *
 * Purpose:
 * - Register script-defined routes and validate local HTTP execution.
 */
import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.runtime.router.MiddlewareResult
import com.koupper.providers.runtime.router.RuntimeRouterProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class Input(
    val token: String = "demo-token",
    val port: Int = 18080
)

@Export
val runtimeRouterDemo: (Input) -> Map<String, Any?> = { input ->
    val rProvider = app.getInstance(RuntimeRouterProvider::class)

    rProvider.registerMiddleware("jwt-auth") { ctx ->
        val auth = ctx.headers["Authorization"]?.firstOrNull().orEmpty()
        if (auth == "Bearer ${input.token}") {
            MiddlewareResult(allowed = true)
        } else {
            MiddlewareResult(allowed = false, statusCode = 401, message = "Invalid token")
        }
    }

    rProvider.registerRouter {
        path { "/users" }

        post<String, Int>(String::class) {
            path { "/create" }
            middlewares { listOf("jwt-auth") }
            script {
                { _ ->
                    200
                }
            }
        }
    }

    val server = rProvider.start(port = input.port, host = "127.0.0.1")

    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:${input.port}/users/create"))
        .header("Authorization", "Bearer ${input.token}")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("hello"))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    rProvider.stop()

    mapOf(
        "ok" to (response.statusCode() == 200),
        "status" to response.statusCode(),
        "response" to response.body(),
        "server" to server
    )
}
