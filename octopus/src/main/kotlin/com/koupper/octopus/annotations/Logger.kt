package com.koupper.octopus.annotations

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Logger(
    val level: String = "INFO",
    val destination: String = "console"
)
