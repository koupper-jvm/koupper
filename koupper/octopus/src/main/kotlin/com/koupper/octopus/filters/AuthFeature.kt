package com.koupper.octopus.filters

import com.koupper.octopus.annotations.Auth
import jakarta.ws.rs.container.DynamicFeature
import jakarta.ws.rs.container.ResourceInfo
import jakarta.ws.rs.core.FeatureContext
import jakarta.ws.rs.ext.Provider

@Provider
class AuthFeature : DynamicFeature {
    override fun configure(resourceInfo: ResourceInfo, context: FeatureContext) {
        val methodHas = resourceInfo.resourceMethod.isAnnotationPresent(Auth::class.java)
        val classHas  = resourceInfo.resourceClass.isAnnotationPresent(Auth::class.java)

        if (methodHas || classHas) {
            context.register(AuthFilter::class.java)
        }
    }
}
