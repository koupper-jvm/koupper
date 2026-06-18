package com.koupper.shared.annotations

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class KoupperVersion(val value: String)
