package com.koupper.octopus.filters

import com.koupper.octopus.annotations.OnSuccess
import jakarta.ws.rs.container.DynamicFeature
import jakarta.ws.rs.container.ResourceInfo
import jakarta.ws.rs.core.FeatureContext
import jakarta.ws.rs.ext.Provider

@Provider
class OnSuccessHttpResponseFeature : DynamicFeature {
    override fun configure(resourceInfo: ResourceInfo, context: FeatureContext) {
        val methodHas = resourceInfo.resourceMethod.isAnnotationPresent(OnSuccess::class.java)
        val classHas  = resourceInfo.resourceClass.isAnnotationPresent(OnSuccess::class.java)

        if (methodHas || classHas) {
            context.register(CaptureRawBodyFilter::class.java)
            context.register(OnSuccessResponseFilter::class.java)
        }
    }
}
