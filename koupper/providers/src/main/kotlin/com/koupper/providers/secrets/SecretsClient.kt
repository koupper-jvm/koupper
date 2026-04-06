package com.koupper.providers.secrets

interface SecretsClient {
    fun get(key: String): String
    fun getOrNull(key: String): String?
    fun getJson(key: String): Map<String, Any?>
    fun put(key: String, value: String): Unit
    fun exists(key: String): Boolean
}
