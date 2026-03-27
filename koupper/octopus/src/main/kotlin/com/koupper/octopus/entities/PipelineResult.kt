package com.koupper.octopus.entities

sealed class PipelineResult<T> {
    data class Ok<T>(val value: T) : PipelineResult<T>()
    data class Error<T>(val error: Throwable) : PipelineResult<T>()

    val isOk get() = this is Ok
}
