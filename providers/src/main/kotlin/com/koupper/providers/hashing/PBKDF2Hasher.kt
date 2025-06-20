package com.koupper.providers.hashing

import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.Base64
import kotlin.experimental.or
import kotlin.experimental.xor

class PBKDF2Hasher : Hasher {
    private val iterations = 65536
    private val keyLength = 256
    private val algorithm = "PBKDF2WithHmacSHA256"

    override fun hash(valueToHash: String, salt: String): String {
        require(valueToHash.isNotBlank()) { "Value to hash must not be blank" }
        require(salt.isNotBlank()) { "Salt must not be blank" }

        val hashBytes = generateHashBytes(valueToHash, salt.toByteArray())
        val saltEncoded = Base64.getEncoder().encodeToString(salt.toByteArray())
        val hashEncoded = Base64.getEncoder().encodeToString(hashBytes)

        return "$saltEncoded:$hashEncoded"
    }

    override fun verify(valueToCheck: String, stored: String): Boolean {
        return try {
            val parts = stored.split(":")
            if (parts.size != 2) return false

            val saltBytes = Base64.getDecoder().decode(parts[0])
            val originalHash = Base64.getDecoder().decode(parts[1])
            val recomputedHash = generateHashBytes(valueToCheck, saltBytes)

            constantTimeEquals(originalHash, recomputedHash)
        } catch (e: Exception) {
            false
        }
    }

    private fun generateHashBytes(value: String, salt: ByteArray): ByteArray {
        val keySpec: KeySpec = PBEKeySpec(value.toCharArray(), salt, iterations, keyLength)
        val factory = SecretKeyFactory.getInstance(algorithm)
        return factory.generateSecret(keySpec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result: Byte = 0
        for (i in a.indices) {
            result = result or (a[i] xor b[i])
        }
        return result == 0.toByte()
    }
}
