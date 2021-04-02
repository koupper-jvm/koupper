package test.project

import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.container.ContainerRequestFilter
import javax.ws.rs.core.Response
import javax.ws.rs.ext.Provider

@Provider
class RestrictedOperationsRequestFilter : ContainerRequestFilter {
    override fun filter(ctx: ContainerRequestContext) {
        if (ctx.language != null && "EN" == ctx.language
                        .language) {
            ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity("Cannot access")
                    .build())
        }
    }
}