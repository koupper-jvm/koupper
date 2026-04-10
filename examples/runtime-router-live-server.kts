/**
 * Runtime Router Live Server Demo
 *
 * Run:
 *   koupper run examples/runtime-router-live-server.kts --serve
 *
 * Then call:
 *   curl -X POST http://127.0.0.1:18081/api/echo -H "Content-Type: application/json" -d '"hello"'
 */
import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.runtime.router.RuntimeRouterProvider

data class Input(
    val host: String = "127.0.0.1",
    val port: Int = 18081
)

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val router = app.getInstance(RuntimeRouterProvider::class)

    router.registerRouter {
        path { "/api" }

        post<String, Map<String, Any>>(String::class) {
            path { "/echo" }
            script {
                { body ->
                    mapOf(
                        "ok" to true,
                        "echo" to body,
                        "timestamp" to System.currentTimeMillis()
                    )
                }
            }
        }
    }

    val server = router.start(port = input.port, host = input.host)
    println("[serve] Runtime Router running at http://${server.host}:${server.port}")
    println("[serve] Endpoint: POST /api/echo")
    println("[serve] Press Ctrl+C to stop")

    try {
        while (!Thread.currentThread().isInterrupted) {
            Thread.sleep(1000)
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    } finally {
        router.stop()
        println("[serve] Runtime Router stopped")
    }

    mapOf("ok" to true, "stopped" to true)
}
