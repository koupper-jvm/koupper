package com.koupper.shared.annotations

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WebRoute(
    val path: String,
    val method: RouteMethod = RouteMethod.GET
)
