package com.koupper.providers.jwt

data class KJWT(
    val token: String,
    val header: Map<String, Any?> = emptyMap(),
    val claims: Map<String, Any?> = emptyMap(),
    val issuer: String? = null,
    val subject: String? = null,
    val audience: List<String> = emptyList(),
    val expiresAtEpochSeconds: Long? = null,
    val issuedAtEpochSeconds: Long? = null,
    val notBeforeEpochSeconds: Long? = null,
    val jwtId: String? = null
) {
    inline fun <reified T> claim(name: String): T? =
        claims[name] as? T

    fun isExpired(now: Long = System.currentTimeMillis() / 1000): Boolean =
        expiresAtEpochSeconds?.let { now >= it } ?: false
}

interface JWT {
    fun encode(rawText: String, algorithmType: JWTAgentEnum): String

    fun decode(
        cypher: String,
        algorithmType: JWTAgentEnum = JWTAgentEnum.HMAC256
    ): KJWT

    fun encode(payload: Map<String, Any?>, algorithmType: JWTAgentEnum): String
}
