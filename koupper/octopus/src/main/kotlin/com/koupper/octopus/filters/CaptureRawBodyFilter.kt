package com.koupper.octopus.filters

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import java.io.ByteArrayInputStream

@Provider
class CaptureRawBodyFilter : ContainerRequestFilter {
    override fun filter(ctx: ContainerRequestContext) {
        if (!ctx.hasEntity()) return

        val bytes = ctx.entityStream.readBytes()
        ctx.setProperty("onSuccess.rawBody", String(bytes, Charsets.UTF_8))
        ctx.entityStream = ByteArrayInputStream(bytes)
    }
}
