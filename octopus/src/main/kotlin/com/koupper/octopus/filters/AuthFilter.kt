package com.koupper.octopus.filters

import com.koupper.container.app
import com.koupper.octopus.annotations.ApiKeyAuth
import com.koupper.octopus.annotations.Auth
import com.koupper.os.env
import com.koupper.providers.aws.dynamo.DynamoClient
import com.koupper.providers.http.ApiKeySession
import com.koupper.providers.http.AuthSession
import com.koupper.providers.jwt.JWT
import com.koupper.providers.jwt.JWTAgentEnum
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ResourceInfo
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response

@Priority(Priorities.AUTHENTICATION)
class AuthFilter : ContainerRequestFilter {

    @Context
    private lateinit var resourceInfo: ResourceInfo

    override fun filter(ctx: ContainerRequestContext) {
        if (ctx.method.equals("OPTIONS", true)) return

        val jwtRequired = isJwtAuthRequired()
        val apiAnn = findApiKeyAuthAnnotation()
        val apiKeyRequired = apiAnn != null
        val requiredScopes = apiAnn?.scopes?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

        val jwtSession = validateJwtSession(ctx)
        if (jwtSession != null) {
            ctx.setProperty("auth.session", jwtSession)
        }

        val apiKeySession = validateApiKeySession(ctx, requiredScopes)
        if (apiKeySession != null) {
            ctx.setProperty("auth.apikey.session", apiKeySession)
        }

        val authorized = when {
            jwtRequired && apiKeyRequired -> jwtSession != null || apiKeySession != null
            jwtRequired -> jwtSession != null
            apiKeyRequired -> apiKeySession != null
            else -> true
        }

        if (!authorized) {
            ctx.abortWith(
                Response.status(401)
                    .entity("""{"error":"unauthorized"}""")
                    .build()
            )
        }
    }

    private fun isJwtAuthRequired(): Boolean {
        val methodHas = resourceInfo.resourceMethod.isAnnotationPresent(Auth::class.java)
        val classHas = resourceInfo.resourceClass.isAnnotationPresent(Auth::class.java)
        return methodHas || classHas
    }

    private fun findApiKeyAuthAnnotation(): ApiKeyAuth? {
        return resourceInfo.resourceMethod.getAnnotation(ApiKeyAuth::class.java)
            ?: resourceInfo.resourceClass.getAnnotation(ApiKeyAuth::class.java)
    }

    private fun validateJwtSession(ctx: ContainerRequestContext): AuthSession? {
        val header = ctx.getHeaderString("Authorization")?.trim().orEmpty()
        if (!header.startsWith("Bearer ", ignoreCase = true)) {
            return null
        }

        val token = header.substringAfter(' ').trim()
        if (token.isBlank()) {
            return null
        }

        val jwt = app.getInstance(JWT::class)
        val decoded = try {
            jwt.decode(token, JWTAgentEnum.HMAC256)
        } catch (_: Exception) {
            return null
        }

        if (decoded.isExpired()) {
            return null
        }

        val userId = decoded.subject ?: return null
        val email = decoded.claim<String>("email") ?: return null
        val role = decoded.claim<String>("role") ?: return null

        return AuthSession(
            userId = userId,
            email = email,
            role = role,
            issuedAtEpochSeconds = decoded.issuedAtEpochSeconds,
            expiresAtEpochSeconds = decoded.expiresAtEpochSeconds,
            token = token
        )
    }

    private fun validateApiKeySession(ctx: ContainerRequestContext, requiredScopes: Set<String>): ApiKeySession? {
        val apiKey = ctx.getHeaderString("X-API-Key")?.trim().orEmpty()
        if (apiKey.isBlank()) {
            return null
        }

        val tableName = env(
            variableName = "KOUPPER_API_KEYS_TABLE",
            required = false,
            allowEmpty = true,
            default = ""
        )

        if (tableName.isBlank()) {
            return null
        }

        val dynamoClient = app.getInstance(DynamoClient::class)
        val items = runCatching { dynamoClient.getAllItemsPaginated(tableName) }
            .getOrNull()
            .orEmpty()

        val appRecord = items.firstOrNull {
            it["secretKey"]?.toString() == apiKey
        } ?: return null

        if (!appRecord["appStatus"].toString().equals("ACTIVE", ignoreCase = true)) {
            return null
        }

        val appScopes = parseScopes(appRecord["scopes"])
        if (requiredScopes.isNotEmpty() && !appScopes.containsAll(requiredScopes)) {
            return null
        }

        val appId = appRecord["appId"]?.toString()?.trim().orEmpty()
        if (appId.isBlank()) {
            return null
        }

        return ApiKeySession(
            appId = appId,
            appName = appRecord["appName"]?.toString().orEmpty(),
            scopes = appScopes.toList(),
            createdBy = appRecord["createdBy"]?.toString().orEmpty()
        )
    }

    private fun parseScopes(raw: Any?): Set<String> {
        if (raw == null) return emptySet()
        return raw.toString()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
