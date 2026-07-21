package com.koupper.octopus.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT

/**
 * JWT-based authentication for Octopus daemon.
 *
 * Supports scoped access control:
 * - `koupper:read`    → HEALTH_CHECK, UPDATING_CHECK
 * - `koupper:execute` → RUN, DEPLOY
 * - `koupper:admin`   → WATCH, CANCEL, and all other commands
 *
 * Backward compatible: if no JWT secret is configured, falls back to
 * the legacy static token check (handled in OctopusProtocol).
 */
object JwtAuth {

    private const val CLAIM_SCOPE = "scope"
    private const val CLAIM_SUB = "sub"

    private val jwtSecret: String?
        get() = System.getProperty("koupper.octopus.jwt.secret")
            ?: System.getenv("KOUPPER_OCTOPUS_JWT_SECRET")
            ?: System.getProperty("koupper.octopus.token")
            ?: System.getenv("KOUPPER_OCTOPUS_TOKEN")

    /** Returns true if JWT auth is configured (secret is present). */
    fun isEnabled(): Boolean = !jwtSecret.isNullOrBlank()

    /**
     * Validates a JWT bearer token and returns its decoded form if valid.
     * Returns null if invalid or expired.
     */
    fun verifyToken(token: String): DecodedJWT? {
        val secret = jwtSecret ?: return null
        return try {
            val algorithm = Algorithm.HMAC256(secret)
            val verifier = JWT.require(algorithm)
                .withIssuer("koupper")
                .acceptLeeway(60) // 60 seconds clock skew
                .build()
            verifier.verify(token)
        } catch (e: JWTVerificationException) {
            null
        }
    }

    /**
     * Extracts scopes from a decoded JWT. Scopes are expected as a space-separated
     * string in the `scope` claim (OAuth2 convention).
     */
    fun extractScopes(jwt: DecodedJWT): Set<String> {
        val scopeClaim = jwt.getClaim(CLAIM_SCOPE).asString() ?: ""
        return scopeClaim.split(" ").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /**
     * Checks if the given scopes authorize the requested command type.
     */
    fun isAuthorized(scopes: Set<String>, commandType: String): Boolean {
        return when (commandType) {
            "HEALTH_CHECK", "UPDATING_CHECK" ->
                scopes.contains("koupper:read") || scopes.contains("koupper:execute") || scopes.contains("koupper:admin")
            "RUN", "DEPLOY" ->
                scopes.contains("koupper:execute") || scopes.contains("koupper:admin")
            "WATCH", "CANCEL" ->
                scopes.contains("koupper:admin")
            else ->
                scopes.contains("koupper:admin")
        }
    }

    /**
     * Generates a JWT token for testing or CLI tooling.
     * Not for production use — production tokens should be issued by an identity provider.
     */
    fun generateToken(subject: String, scopes: List<String>, expiresInSeconds: Long = 3600): String? {
        val secret = jwtSecret ?: return null
        return try {
            val algorithm = Algorithm.HMAC256(secret)
            JWT.create()
                .withIssuer("koupper")
                .withSubject(subject)
                .withClaim(CLAIM_SCOPE, scopes.joinToString(" "))
                .withExpiresAt(java.util.Date(System.currentTimeMillis() + expiresInSeconds * 1000))
                .sign(algorithm)
        } catch (e: Exception) {
            null
        }
    }
}
