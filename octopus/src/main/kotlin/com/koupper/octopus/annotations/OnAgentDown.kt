package com.koupper.octopus.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OnAgentDown(
    val agent: String,
    val cooldown: String = "5m"
)
