package com.koupper.octopus.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OnFileCreated(
    val path: String,
    val cooldown: String = "0"
)
