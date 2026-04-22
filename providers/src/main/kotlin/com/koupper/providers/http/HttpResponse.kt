package com.koupper.providers.http

import io.vertx.core.json.JsonObject
import okhttp3.Response

class HttpResponse(private val response: Response?) {

    fun asJson(): JsonObject {
        val body = response?.body?.string() ?: "{}"
        return JsonObject(body)
    }

    fun asString(): String? {
        return response?.body?.string()
    }

    fun code(): Int {
        return response?.code ?: -1
    }

    fun headers(): Map<String, String> {
        return response?.headers?.toMultimap()?.mapValues { it.value.joinToString(",") } ?: emptyMap()
    }

    fun isSuccessful(): Boolean {
        return response?.isSuccessful ?: false
    }

    fun isRedirect(): Boolean {
        val code = response?.code ?: return false
        return code in 300..399
    }

    fun close() {
        response?.close()
    }
}
