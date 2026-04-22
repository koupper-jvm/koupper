package com.koupper.octopus.filters

import com.koupper.octopus.annotations.ApiKeyAuth
import com.koupper.octopus.annotations.Auth
import jakarta.ws.rs.container.DynamicFeature
import jakarta.ws.rs.container.ResourceInfo
import jakarta.ws.rs.core.FeatureContext
import jakarta.ws.rs.ext.Provider

@Provider
class AuthFeature : DynamicFeature {
    override fun configure(resourceInfo: ResourceInfo, context: FeatureContext) {
        val methodHasAuth = resourceInfo.resourceMethod.isAnnotationPresent(Auth::class.java)
        val classHasAuth = resourceInfo.resourceClass.isAnnotationPresent(Auth::class.java)
        val methodHasApiKeyAuth = resourceInfo.resourceMethod.isAnnotationPresent(ApiKeyAuth::class.java)
        val classHasApiKeyAuth = resourceInfo.resourceClass.isAnnotationPresent(ApiKeyAuth::class.java)

        if (methodHasAuth || classHasAuth || methodHasApiKeyAuth || classHasApiKeyAuth) {
            context.register(AuthFilter::class.java)
        }
    }
}
