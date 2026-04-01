package com.koupper.providers.jwt

import com.beust.klaxon.JsonObject

fun JWT.encode(payload: Map<String, Any?>, algorithmType: JWTAgentEnum): String {
    return when (this) {
        is JWTAgent -> this.encode(payload, algorithmType) // usa la sobrecarga concreta si existe
        else -> {
            val json = JsonObject(payload).toJsonString()
            this.encode(json, algorithmType)
        }
    }
}