package com.koupper.octopus.annotations

import com.koupper.octopus.events.DomainEvent
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import kotlin.reflect.KClass

interface SuccessEventBuilder<E : DomainEvent> {
    fun build(req: ContainerRequestContext, res: ContainerResponseContext): E?
}

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OnSuccess(
    val builder: KClass<out SuccessEventBuilder<out DomainEvent>>
)
