package com.koupper.octopus.filters

import com.koupper.octopus.annotations.Authorize
import com.koupper.providers.http.AuthSession
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ResourceInfo
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response

@Priority(Priorities.AUTHORIZATION)
class AuthorizationFilter : ContainerRequestFilter {

    @Context
    private lateinit var resourceInfo: ResourceInfo

    override fun filter(ctx: ContainerRequestContext) {
        if (ctx.method.equals("OPTIONS", true)) return

        val session = ctx.getProperty("auth.session") as? AuthSession
            ?: run {
                ctx.abortWith(Response.status(401).entity("""{"error":"unauthorized"}""").build())
                return
            }

        val ann = resourceInfo.resourceMethod.getAnnotation(Authorize::class.java)
            ?: resourceInfo.resourceClass.getAnnotation(Authorize::class.java)
            ?: return

        val policy = try {
            ann.value.java.getDeclaredConstructor().newInstance()
        } catch (_: Exception) {
            ctx.abortWith(
                Response.status(500)
                    .entity("""{"error":"auth_policy_init_failed"}""")
                    .build()
            )
            return
        }

        val allowed = try {
            policy.check(ctx)
        } catch (_: Exception) {
            false
        }

        if (!allowed) {
            ctx.abortWith(
                Response.status(403)
                    .entity("""{"error":"forbidden"}""")
                    .build()
            )
        }
    }
}
