/**
 * REST API usage example.
 *
 * The HTTP REST API runs on port 9997 with these endpoints:
 *
 * | Method | Endpoint | Auth | Description |
 * |--------|----------|------|-------------|
 * | POST | /api/v1/run | JWT execute | Run a script |
 * | GET | /api/v1/health | none | Health check |
 * | GET | /api/v1/status | JWT read | Daemon metrics |
 * | GET | /api/v1/jobs | JWT read | List job queues |
 *
 * Example curl commands:
 *
 * ```bash
 * # Health check (no auth)
 * curl http://localhost:9997/api/v1/health
 *
 * # Run a script (JWT required)
 * curl -X POST http://localhost:9997/api/v1/run \
 *   -H "Authorization: Bearer $JWT_TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{"script": "examples/scripts/basic-export.kts", "params": {}}'
 *
 * # Get daemon status
 * curl http://localhost:9997/api/v1/status \
 *   -H "Authorization: Bearer $JWT_TOKEN"
 * ```
 */
fun main() {
    println("See REST API documentation in HttpApiServer.kt")
    println("Server runs on http://localhost:9997")
}

/**
 * Observability — structured request/response logging via afterRequestHook.
 *
 * Register once in your server setup. The hook fires after every completed
 * (non-streaming) request with the real response status and total duration
 * including handler execution.
 *
 * Example output (JSON appender):
 *   {"event":"request_received","requestId":"abc","method":"GET","path":"/api/v1/quizzes","service":"my-api"}
 *   {"event":"request_completed","status":"200","durationMs":"47","requestId":"abc","service":"my-api"}
 */
fun setupObservability(router: com.koupper.providers.runtime.router.RuntimeRouterProvider, logger: com.koupper.logging.LoggerCore) {
    val requestId = java.util.UUID.randomUUID().toString()

    router.registerMiddleware("request-log") { ctx ->
        com.koupper.logging.LoggerContext.put("requestId", requestId)
        com.koupper.logging.LoggerContext.put("method", ctx.method)
        com.koupper.logging.LoggerContext.put("path", ctx.path)
        logger.info { "request_received" }
        com.koupper.providers.runtime.router.MiddlewareResult(allowed = true, statusCode = 200, message = "")
    }

    com.koupper.shared.runtime.GlobalRouteRegistry.afterRequestHook = { statusCode, durationMs ->
        com.koupper.logging.LoggerContext.put("status", statusCode.toString())
        com.koupper.logging.LoggerContext.put("durationMs", durationMs.toString())
        logger.info { "request_completed" }
        com.koupper.logging.LoggerContext.clear()
    }
}
