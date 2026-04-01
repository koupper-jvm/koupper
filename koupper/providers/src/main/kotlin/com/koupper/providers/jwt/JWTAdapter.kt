import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.DecodedJWT
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import com.koupper.providers.jwt.KJWT

private val mapper = jacksonObjectMapper()

internal fun DecodedJWT.toKJWT(token: String): KJWT {
    return KJWT(
        token = token,
        header = parseJsonMap(header),
        claims = claims.mapValues { it.value.toAny() },
        issuer = issuer,
        subject = subject,
        audience = audience ?: emptyList(),
        expiresAtEpochSeconds = expiresAt?.time?.div(1000),
        issuedAtEpochSeconds = issuedAt?.time?.div(1000),
        notBeforeEpochSeconds = notBefore?.time?.div(1000),
        jwtId = id
    )
}

private fun parseJsonMap(json: String?): Map<String, Any?> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        mapper.readValue(json, object : TypeReference<Map<String, Any?>>() {})
    }.getOrElse { emptyMap() }
}

private fun Claim.toAny(): Any? {
    asBoolean()?.let { return it }
    asInt()?.let { return it }
    asLong()?.let { return it }
    asDouble()?.let { return it }
    asString()?.let { return it }

    runCatching { asArray(String::class.java)?.toList() }.getOrNull()?.let { return it }
    runCatching { asArray(Int::class.java)?.toList() }.getOrNull()?.let { return it }
    runCatching { asArray(Long::class.java)?.toList() }.getOrNull()?.let { return it }

    asMap()?.let { return it }
    asList(Any::class.java)?.let { return it }

    return null
}
