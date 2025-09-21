package com.koupper.providers.files

// Usa la extensión sobre JSONFileHandler<*> para ambos casos.
// NO declares otra extensión con el mismo nombre sobre JSONFileHandler<T>,
// porque choca por type erasure.

/**
 * Lee el JSON `text` y lo convierte a T usando tu handler.
 * Sirve tanto si el handler viene tipado como si viene de DI (JSONFileHandler<*>).
 */
inline fun <reified T> JSONFileHandler<*>.readAs(text: String?): T? {
    if (text == null) return null
    @Suppress("UNCHECKED_CAST")
    val typed = this as JSONFileHandler<T>
    return typed.read(text).toType<T>()
}

/**
 * Serializa cualquier `Any?` usando tu handler (sin usar Jackson fuera).
 */
fun JSONFileHandler<Any?>.toJsonAny(data: Any?): String =
    this.toJsonString(data)
