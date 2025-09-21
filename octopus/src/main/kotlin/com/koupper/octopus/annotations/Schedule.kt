package com.koupper.octopus.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Schedule(
    val dateTime: String
)

