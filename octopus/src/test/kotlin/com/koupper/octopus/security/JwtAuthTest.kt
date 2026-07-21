package com.koupper.octopus.security

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.*

class JwtAuthTest : AnnotationSpec() {

    @BeforeEach
    fun setup() {
        // Set a test secret for JWT generation/verification
        System.setProperty("koupper.octopus.jwt.secret", "test-secret-key-for-jwt-unit-tests")
    }

    @AfterEach
    fun teardown() {
        System.clearProperty("koupper.octopus.jwt.secret")
    }

    @Test
    fun `should generate and verify a valid JWT token`() {
        val token = JwtAuth.generateToken("test-user", listOf("koupper:execute"))
        assertNotNull(token, "Token should be generated")

        val decoded = JwtAuth.verifyToken(token)
        assertNotNull(decoded, "Token should be valid")
        assertEquals("test-user", decoded.subject)
    }

    @Test
    fun `should reject an invalid JWT token`() {
        val decoded = JwtAuth.verifyToken("invalid.token.here")
        assertNull(decoded, "Invalid token should be rejected")
    }

    @Test
    fun `should extract scopes from JWT`() {
        val token = JwtAuth.generateToken("user", listOf("koupper:read", "koupper:execute"))
        assertNotNull(token)

        val decoded = JwtAuth.verifyToken(token)!!
        val scopes = JwtAuth.extractScopes(decoded)
        assertEquals(setOf("koupper:read", "koupper:execute"), scopes)
    }

    @Test
    fun `should authorize read scope for HEALTH_CHECK`() {
        assertTrue(JwtAuth.isAuthorized(setOf("koupper:read"), "HEALTH_CHECK"))
        assertTrue(JwtAuth.isAuthorized(setOf("koupper:execute"), "HEALTH_CHECK"))
        assertTrue(JwtAuth.isAuthorized(setOf("koupper:admin"), "HEALTH_CHECK"))
    }

    @Test
    fun `should authorize execute scope for RUN`() {
        assertFalse(JwtAuth.isAuthorized(setOf("koupper:read"), "RUN"))
        assertTrue(JwtAuth.isAuthorized(setOf("koupper:execute"), "RUN"))
        assertTrue(JwtAuth.isAuthorized(setOf("koupper:admin"), "RUN"))
    }

    @Test
    fun `should require admin scope for WATCH`() {
        assertFalse(JwtAuth.isAuthorized(setOf("koupper:read"), "WATCH"))
        assertFalse(JwtAuth.isAuthorized(setOf("koupper:execute"), "WATCH"))
        assertTrue(JwtAuth.isAuthorized(setOf("koupper:admin"), "WATCH"))
    }

    @Test
    fun `should require admin scope for CANCEL`() {
        assertFalse(JwtAuth.isAuthorized(setOf("koupper:execute"), "CANCEL"))
        assertTrue(JwtAuth.isAuthorized(setOf("koupper:admin"), "CANCEL"))
    }

    @Test
    fun `should return null when JWT secret is not configured`() {
        System.clearProperty("koupper.octopus.jwt.secret")
        val token = JwtAuth.generateToken("user", listOf("koupper:admin"))
        assertNull(token, "Should not generate token without secret")
    }

    @Test
    fun `isEnabled should be true when secret is configured`() {
        assertTrue(JwtAuth.isEnabled(), "JWT should be enabled when secret is set")
    }

    @Test
    fun `isEnabled should be false when secret is not configured`() {
        System.clearProperty("koupper.octopus.jwt.secret")
        assertFalse(JwtAuth.isEnabled(), "JWT should be disabled when secret is not set")
    }
}
