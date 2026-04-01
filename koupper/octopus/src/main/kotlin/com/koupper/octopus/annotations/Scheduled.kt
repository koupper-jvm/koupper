package com.koupper.octopus.annotations

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Scheduled(
    val rate: Long = 0L,
    val cron: String = "",
    val configId: String = "",
    val debug: Boolean = false,
    val delay: Long = 0L,
    val at: String = ""
)