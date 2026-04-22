package com.koupper.providers.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.koupper.os.env
import toKJWT
import java.lang.StringBuilder

enum class JWTAgentEnum {
    HMAC256
}

class JWTAgent : com.koupper.providers.jwt.JWT {
    private val secret: String = env("JWT_SECRET")

    override fun encode(rawText: String, algorithmType: JWTAgentEnum): String {
        val payload = parsePayload(rawText)
        return signPayload(payload, algorithmType)
    }

    override fun encode(payload: Map<String, Any?>, algorithmType: JWTAgentEnum): String {
        val json = JsonObject(payload)
        return signPayload(json, algorithmType)
    }

    override fun decode(cypher: String, algorithmType: JWTAgentEnum): KJWT {
        val algorithm = when (algorithmType) {
            JWTAgentEnum.HMAC256 -> Algorithm.HMAC256(secret)
        }

        return JWT.require(algorithm)
            .build()
            .verify(cypher).toKJWT(token = cypher)
    }

    private fun parsePayload(rawText: String): JsonObject {
        return try {
            Parser.default().parse(StringBuilder(rawText)) as? JsonObject
                ?: throw IllegalArgumentException("Invalid payload format.")
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse payload: ${e.message}")
        }
    }

    private fun signPayload(payload: JsonObject, algorithmType: JWTAgentEnum): String {
        val algorithm = when (algorithmType) {
            JWTAgentEnum.HMAC256 -> Algorithm.HMAC256(secret)
        }

        return JWT.create()
            .withPayload(payload)
            .sign(algorithm)
    }
}

