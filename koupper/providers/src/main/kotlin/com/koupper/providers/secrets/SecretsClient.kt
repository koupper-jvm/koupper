package com.koupper.providers.secrets

interface SecretsClient {
    fun get(key: String): String
    fun getOrNull(key: String): String?
    fun getJson(key: String): Map<String, Any?>
    fun put(key: String, value: String)
    fun exists(key: String): Boolean
    /** Removes the secret with the given key. Returns true if the key existed and was removed. */
    fun delete(key: String): Boolean
    /** Returns the set of all known secret keys (from file cache; does not enumerate env vars). */
    fun list(): Set<String>
}
