package com.koupper.providers.http

import io.github.rybalkinsd.kohttp.ext.asString
import io.vertx.core.json.JsonObject
import okhttp3.Response

class HttpResponse(private val response: Response?) {
    fun asJson(): JsonObject {
        val response = response?.body()?.string()

        return JsonObject(response)
    }

    fun asString(): String? {
        return response?.asString()
    }

    fun isSuccessful(): Boolean {
        return response?.isSuccessful ?: false
    }

    fun isRedirect(): Boolean {
        return response?.isRedirect ?: false
    }
}