package com.koupper.octopus.annotations

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class JobsListener(
    val time: Long = 5000,
    val queue: String = "job-callbacks",
    val driver: String = "default",
    val debug: Boolean = false
)
