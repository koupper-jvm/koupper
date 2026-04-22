package com.koupper.octopus.filters

import com.koupper.octopus.annotations.Authorize
import jakarta.ws.rs.container.DynamicFeature
import jakarta.ws.rs.container.ResourceInfo
import jakarta.ws.rs.core.FeatureContext
import jakarta.ws.rs.ext.Provider

@Provider
class AuthorizationFeature : DynamicFeature {
    override fun configure(resourceInfo: ResourceInfo, context: FeatureContext) {
        val methodAnn = resourceInfo.resourceMethod.getAnnotation(Authorize::class.java)
        val classAnn  = resourceInfo.resourceClass.getAnnotation(Authorize::class.java)

        if (methodAnn != null || classAnn != null) {
            context.register(AuthorizationFilter::class.java)
        }
    }
}
