package com.koupper.providers.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.providers.extensions.getProperty
import com.koupper.providers.parsing.JsonToObject
import com.koupper.providers.parsing.TextJsonParser
import java.io.File
import java.lang.StringBuilder
import java.util.*

enum  class JWTAgentEnum {
    HMAC256
}

class JWTAgent(container: Container) : com.koupper.providers.jwt.JWT {
    private lateinit var secret: String
    private val container: Container = container

    init {
        val secretOnEnv = container.env("JWT_SECRET")

        if (secretOnEnv === "undefined") {
            try {
                val secretOnFile = File(".env").getProperty("JWT_SECRET")

                if (secretOnFile === "undefined") {
                    throw Exception("The JWT_SECRET should be present in: an environment variable||an env file (.env) to use this provider")
                } else {
                    this.secret = secret
                }
            } catch (e: Exception) {
                throw Exception("The JWT_SECRET should be present in: an environment variable||an env file (.env) to use this provider")
            }
        } else {
            this.secret = secretOnEnv
        }
    }

    override fun encode(rawText: String, algorithmType: JWTAgentEnum): String {
        val payload = Parser.default().parse(StringBuilder(rawText)) as JsonObject

        if (algorithmType === JWTAgentEnum.HMAC256) {
            JWT.create().withPayload(payload).sign(Algorithm.HMAC256(this.secret))
        }

        return JWT.create().withPayload(payload).sign(Algorithm.HMAC256(this.secret))
    }

    override fun decode(cypher: String, algorithmType: JWTAgentEnum): DecodedJWT {
        if (algorithmType === JWTAgentEnum.HMAC256) {
            JWT.require(Algorithm.HMAC256(this.secret)).build().verify(cypher)
        }

        return JWT.require(Algorithm.HMAC256(this.secret)).build().verify(cypher)
    }
}