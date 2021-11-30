package com.koupper.providers.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.koupper.container.interfaces.Container
import com.koupper.os.env
import com.koupper.shared.getProperty
import java.io.File
import java.lang.StringBuilder

enum  class JWTAgentEnum {
    HMAC256
}

class JWTAgent : com.koupper.providers.jwt.JWT {
    private val secret: String = env("JWT_SECRET")

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