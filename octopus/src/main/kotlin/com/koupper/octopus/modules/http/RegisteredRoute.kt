package com.koupper.octopus.modules.http

import kotlin.reflect.KClass

data class RegisteredRoute(
    val method: String,
    val path: String,
    val middlewares: List<String> = emptyList(),
    val inputType: KClass<*>? = null,
    val outputType: KClass<*>? = null,
    val script: (Any?) -> Any?
)
