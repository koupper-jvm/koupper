package server

import com.koupper.octopus.createDefaultConfiguration
import jakarta.ws.rs.core.UriBuilder
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory
import org.glassfish.jersey.server.ResourceConfig
import java.net.URI
import java.util.logging.Logger

const val BASE_URL = "http://localhost"
const val PORT = 8080

val logger: Logger = Logger.getLogger("ServerLogger")
val executor = createDefaultConfiguration()

class Setup : ResourceConfig() {
    init {
        packages("http.controllers")
    }
}

fun main() {
    val url: URI = UriBuilder.fromUri(BASE_URL)
        .port(PORT)
        .build()

    logger.info("Starting server at $url")

    try {
        val httpServer = GrizzlyHttpServerFactory.createHttpServer(url, Setup(), true)

        setupShutdownHook(httpServer)

        if (System.getenv("SHUTDOWN_TYPE") == "INPUT") {
            logger.info("Press any key to shutdown the server...")
            readLine()
            logger.info("Shutting down the server from input...")
            httpServer.shutdownNow()
        } else {
            logger.info("Server is running. Press Ctrl+C to shutdown.")
            Thread.currentThread().join()
        }
    } catch (e: Exception) {
        logger.severe("Error starting server: ${e.message}")
    }
}

fun setupShutdownHook(httpServer: org.glassfish.grizzly.http.server.HttpServer) {
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down the server from shutdown hook...")
        httpServer.shutdownNow()
    })
}
