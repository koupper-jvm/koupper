package com.koupper.providers.files

/**
 * Handles JSON serialization and deserialization.
 *
 * Usage:
 *   // Deserialize
 *   val user = json().read(rawJson).toType<User>()
 *   val user = json().readFrom("/path/config.json").toType<User>()
 *
 *   // Serialize
 *   val jsonStr = json().toJson(myObject)
 *
 *   // Shorthand extensions
 *   val user = rawJson.fromJson<User>()
 *   val jsonStr = myObject.toJson()
 */
interface JSONFileHandler {
    /** Load JSON content from a file path and hold it for deserialization. */
    fun readFrom(filePath: String): JSONFileHandler

    /** Hold a raw JSON string for deserialization. */
    fun read(json: String): JSONFileHandler

    /** Return the raw JSON string currently held. */
    fun text(): String

    /** Serialize any value to a JSON string. */
    fun toJson(data: Any?): String
}
