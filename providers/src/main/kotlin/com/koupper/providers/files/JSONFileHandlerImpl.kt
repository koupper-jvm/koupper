package com.koupper.providers.files

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.container.app

// Shared mapper — thread-safe after configuration.
@PublishedApi
internal val MAPPER = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// @PublishedApi so public inline functions can call into MAPPER across modules.
@PublishedApi
internal fun <T> mapperReadValue(json: String, clazz: Class<T>): T = MAPPER.readValue(json, clazz)

@PublishedApi
internal fun <T> mapperReadValue(json: String, typeReference: TypeReference<T>): T = MAPPER.readValue(json, typeReference)

@PublishedApi
internal fun mapperWriteValue(data: Any?): String = MAPPER.writeValueAsString(data)

class JSONFileHandlerImpl : JSONFileHandler {
    private var json: String? = null

    override fun readFrom(filePath: String): JSONFileHandler {
        json = app.getInstance(FileHandler::class).load(filePath).readText(Charsets.UTF_8)
        return this
    }

    override fun read(json: String): JSONFileHandler {
        this.json = json
        return this
    }

    override fun text(): String =
        json ?: throw IllegalStateException("No JSON loaded — call read() or readFrom() first.")

    override fun toJson(data: Any?): String = mapperWriteValue(data)
}

// ── Deserialization extensions ────────────────────────────────────────────────

class JsonParseException(message: String, cause: Throwable) : Exception(message, cause)

/** Deserialize the loaded JSON to [T]. */
inline fun <reified T> JSONFileHandler.toType(): T = try {
    mapperReadValue(text(), object : TypeReference<T>() {})
} catch (e: Exception) {
    throw JsonParseException("Failed to parse JSON to ${T::class.simpleName}: ${e.message}", e)
}

/** Deserialize using a runtime [Class] reference — for use from non-reified contexts. */
fun JSONFileHandler.toType(clazz: Class<*>): Any? = try {
    mapperReadValue(text(), clazz)
} catch (e: Exception) {
    throw JsonParseException("Failed to parse JSON to ${clazz.name}: ${e.message}", e)
}

/** One-shot: load [json] and deserialize to [T], or null if blank/invalid. */
inline fun <reified T> JSONFileHandler.readAs(json: String?): T? {
    if (json.isNullOrBlank()) return null
    return try { read(json).toType<T>() } catch (_: Exception) { null }
}

sealed class JsonParseResult<out T> {
    data class Ok<T>(val value: T) : JsonParseResult<T>()
    data class Err(val code: String) : JsonParseResult<Nothing>()
}

/** One-shot: load [json] and deserialize to [T], returning a typed result instead of throwing. */
inline fun <reified T> JSONFileHandler.tryReadAs(json: String?): JsonParseResult<T> {
    if (json.isNullOrBlank()) return JsonParseResult.Err("empty_body")
    return try { JsonParseResult.Ok(read(json).toType<T>()) }
    catch (_: Exception) { JsonParseResult.Err("invalid_json") }
}

// ── Convenience extensions on Any / String / Map ─────────────────────────────

/** Serialize this object to a JSON string. */
fun Any?.toJson(): String = mapperWriteValue(this)

/** Serialize this object to a pretty-printed JSON string. */
fun Any?.toJsonPretty(): String = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this)

/** Deserialize this JSON string to [T]. */
inline fun <reified T> String.fromJson(): T = mapperReadValue(this, object : TypeReference<T>() {})

// ── Typed accessors on dynamic maps ──────────────────────────────────────────

/** Read [key] as Int, coercing Number if needed. */
fun Map<String, Any?>.getInt(key: String): Int? = (this[key] as? Number)?.toInt()

/** Read [key] as Long, coercing Number if needed. */
fun Map<String, Any?>.getLong(key: String): Long? = (this[key] as? Number)?.toLong()

/** Read [key] as String (calls toString on non-null values). */
fun Map<String, Any?>.getString(key: String): String? = this[key]?.toString()

/** Read [key] as Boolean. */
fun Map<String, Any?>.getBool(key: String): Boolean? = this[key] as? Boolean

/** Read [key] as a nested object map. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.getObject(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

/** Read [key] as a list. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.getList(key: String): List<Any?>? = this[key] as? List<Any?>

/**
 * Converts a Map (typically raw input) to a structured Data Class [T].
 * This enables "Deep-Type Resolution" for script inputs.
 */
inline fun <reified T> Map<String, Any?>.toType(): T = try {
    MAPPER.convertValue(this, object : TypeReference<T>() {})
} catch (e: Exception) {
    throw JsonParseException("Failed to convert Map to ${T::class.simpleName}: ${e.message}", e)
}
