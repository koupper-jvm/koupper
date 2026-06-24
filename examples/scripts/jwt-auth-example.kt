import com.koupper.octopus.security.JwtAuth

/**
 * JWT authentication example.
 *
 * Demonstrates token generation and verification with scopes.
 */
fun main() {
    val secret = "your-256-bit-secret-here"
    val auth = JwtAuth(secret)

    // Generate a token with execute scope
    val token = auth.generate(
        subject = "user-123",
        scopes = listOf("koupper:read", "koupper:execute"),
        ttlHours = 24
    )
    println("Generated JWT: $token")

    // Verify and decode
    val decoded = auth.verify(token)
    println("Subject: ${decoded.subject}")
    println("Scopes: ${decoded.scopes}")
    println("Has read scope: ${decoded.hasScope("koupper:read")}")
    println("Has admin scope: ${decoded.hasScope("koupper:admin")}")
}
