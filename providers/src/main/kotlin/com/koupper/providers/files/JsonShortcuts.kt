package com.koupper.providers.files

inline fun <reified T> JSONFileHandler<*>.readTo(body: String?): T? {
    if (body.isNullOrBlank()) return null
    @Suppress("UNCHECKED_CAST")
    val typed = this as JSONFileHandler<T>
    return try {
        typed.read(body).toType<T>()
    } catch (_: Exception) {
        null
    }
}

sealed class JsonParseResult<out T> {
    data class Ok<T>(val value: T): JsonParseResult<T>()
    data class Err(val code: String): JsonParseResult<Nothing>()
}

inline fun <reified T> JSONFileHandler<*>.tryReadTo(body: String?): JsonParseResult<T> {
    if (body.isNullOrBlank()) return JsonParseResult.Err("empty_body")
    @Suppress("UNCHECKED_CAST")
    val typed = this as JSONFileHandler<T>
    return try {
        JsonParseResult.Ok(typed.read(body).toType<T>())
    } catch (_: Exception) {
        JsonParseResult.Err("invalid_json")
    }
}
