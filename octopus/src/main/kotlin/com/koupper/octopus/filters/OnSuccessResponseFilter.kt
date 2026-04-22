package com.koupper.octopus.filters

import com.koupper.logging.GlobalLogger
import com.koupper.logging.GlobalLogger.log
import com.koupper.octopus.annotations.OnSuccess
import com.koupper.octopus.annotations.SuccessEventBuilder
import com.koupper.octopus.events.DomainEvent
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.ext.Provider

@Provider
class OnSuccessResponseFilter : ContainerResponseFilter {

    @Context
    private lateinit var resourceInfo: jakarta.ws.rs.container.ResourceInfo

    override fun filter(req: ContainerRequestContext, res: ContainerResponseContext) {
        if (res.status !in 200..299) return

        val ann =
            resourceInfo.resourceMethod.getAnnotation(OnSuccess::class.java)
                ?: resourceInfo.resourceClass.getAnnotation(OnSuccess::class.java)
                ?: return

        val builder = resolveBuilder(ann) ?: return

        val event: DomainEvent = try {
            builder.build(req, res)
        } catch (_: Exception) {
            null
        } ?: return

        try {
            GlobalEventBus.bus.publish(event)
        } catch (_: Exception) {
            // no rompas la response
        }
    }

    private fun resolveBuilder(ann: OnSuccess): SuccessEventBuilder<out DomainEvent>? {
        ann.builder.objectInstance?.let { return it }
        return try {
            ann.builder.java.getDeclaredConstructor().newInstance()
        } catch (_: Exception) {
            null
        }
    }
}
