package com.koupper.octopus.annotations

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class JobsListener(
    val time: Long = 5000,
    val debug: Boolean = false,
    val configId: String = "DEFAULT"
)
