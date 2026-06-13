package com.koupper.octopus.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OnQueueEmpty(
    val queue: String = "default",
    val cooldown: String = "60m"
)
