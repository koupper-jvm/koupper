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
