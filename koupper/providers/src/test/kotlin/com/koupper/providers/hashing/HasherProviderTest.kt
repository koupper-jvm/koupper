package com.koupper.providers.hashing

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HasherProviderTest : AnnotationSpec() {

    private val hasher: Hasher = PBKDF2Hasher()

    @Test
    fun `hash returns non-empty string`() {
        val result = hasher.hash("mysecret", "randomsalt")
        assertTrue(result.isNotBlank())
    }

    @Test
    fun `hash includes salt prefix in output`() {
        val result = hasher.hash("password", "mysalt")
        assertTrue(result.contains(":"), "Expected salt:hash format")
    }

    @Test
    fun `verify returns true for correct password`() {
        val stored = hasher.hash("correct-password", "somesalt")
        assertTrue(hasher.verify("correct-password", stored))
    }

    @Test
    fun `verify returns false for wrong password`() {
        val stored = hasher.hash("correct-password", "somesalt")
        assertFalse(hasher.verify("wrong-password", stored))
    }

    @Test
    fun `same password with different salts produces different hashes`() {
        val h1 = hasher.hash("password", "salt1")
        val h2 = hasher.hash("password", "salt2")
        assertNotEquals(h1, h2)
    }

    @Test
    fun `verify returns false for malformed stored value`() {
        assertFalse(hasher.verify("any", "notavalidhash"))
    }
}
