package com.koupper.octopus.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OnJobFailed(
    val queue: String = "default",
    val cooldown: String = "30m"
)
