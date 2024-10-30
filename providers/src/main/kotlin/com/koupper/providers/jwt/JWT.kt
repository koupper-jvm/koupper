package com.koupper.providers.jwt

import com.auth0.jwt.interfaces.DecodedJWT

interface JWT {
    fun encode(rawText: String, algorithmType: JWTAgentEnum): String

    fun decode(cypher: String, algorithmType: JWTAgentEnum = JWTAgentEnum.HMAC256): DecodedJWT
}