package server

import com.koupper.container.app
import com.koupper.octopus.createDefaultConfiguration
import com.koupper.os.env
import com.koupper.providers.runtime.router.RuntimeRouterProvider
import java.util.logging.Logger

const val PORT = 8080
val logger: Logger = Logger.getLogger("KoupperServer")

fun main() {
    val serverPort = env("PORT", required = false, default = PORT.toString()).toInt()

    // 1. Inicializamos Koupper
    createDefaultConfiguration()

    val router = app.getInstance(RuntimeRouterProvider::class)

    logger.info("🚀 Koupper native server starting...")

    // 2. Registramos rutas base
    router.registerRouter {
        get<Any> {
            path { "/health" }
            script { { mapOf("status" to "UP") } }
        }
    }

    // 3. Arrancamos el motor HTTP
    logger.info("📡 Starting server at http://0.0.0.0:$serverPort")
    router.start(port = serverPort)

    println("✅ Koupper Backend is LIVE. Press Ctrl+C to stop.")
    Thread.currentThread().join()
}
