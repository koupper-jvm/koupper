package com.koupper.octopus.filters

import com.koupper.container.app
import com.koupper.providers.http.AuthSession
import com.koupper.providers.jwt.JWT
import com.koupper.providers.jwt.JWTAgentEnum
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response

@Priority(Priorities.AUTHENTICATION)
class AuthFilter : ContainerRequestFilter {
    override fun filter(ctx: ContainerRequestContext) {
        if (ctx.method.equals("OPTIONS", true)) return

        val token = ctx.getHeaderString("Authorization")
            ?.removePrefix("Bearer ")
            ?.removePrefix("bearer ")
            ?.trim()

        if (token.isNullOrBlank()) {
            ctx.abortWith(
                Response.status(401)
                    .entity("""{"error":"unauthorized"}""")
                    .build()
            )
            return
        }

        val jwt = app.getInstance(JWT::class)

        val decoded = try {
            jwt.decode(token, JWTAgentEnum.HMAC256)
        } catch (_: Exception) {
            ctx.abortWith(
                Response.status(401)
                    .entity("""{"error":"invalid_token"}""")
                    .build()
            )
            return
        }

        if (decoded.isExpired()) {
            ctx.abortWith(
                Response.status(401)
                    .entity("""{"error":"token_expired"}""")
                    .build()
            )
            return
        }

        val userId = decoded.subject
            ?: run {
                ctx.abortWith(Response.status(401).entity("""{"error":"invalid_token"}""").build())
                return
            }

        val email = decoded.claim<String>("email")
            ?: run {
                ctx.abortWith(Response.status(401).entity("""{"error":"invalid_token"}""").build())
                return
            }

        val role = decoded.claim<String>("role")
            ?: run {
                ctx.abortWith(Response.status(401).entity("""{"error":"invalid_token"}""").build())
                return
            }

        val session = AuthSession(
            userId = userId,
            email = email,
            role = role,
            issuedAtEpochSeconds = decoded.issuedAtEpochSeconds,
            expiresAtEpochSeconds = decoded.expiresAtEpochSeconds,
            token = token
        )

        ctx.setProperty("auth.session", session)
    }
}
