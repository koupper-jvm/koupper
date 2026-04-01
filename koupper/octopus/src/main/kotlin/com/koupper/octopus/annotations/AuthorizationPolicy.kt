package com.koupper.octopus.annotations

import jakarta.ws.rs.container.ContainerRequestContext

interface AuthorizationPolicy {
    fun check(ctx: ContainerRequestContext): Boolean
}
