package com.koupper.providers

import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.dispatch.SenderServiceProvider
import com.koupper.providers.http.HttpServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
import com.koupper.providers.parsing.JsonToObject
import com.koupper.providers.parsing.TextJsonParser
import com.koupper.providers.parsing.TextJsonParserServiceProvider
import com.koupper.providers.parsing.TextParserServiceProvider
import io.github.rybalkinsd.kohttp.dsl.httpGet
import io.github.rybalkinsd.kohttp.util.json
import okhttp3.Response
import kotlin.reflect.KClass

class ServiceProviderManager {
    fun listProviders(): List<KClass<*>> {
        return listOf(
                TextParserServiceProvider::class,
                DBServiceProvider::class,
                SenderServiceProvider::class,
                LoggerServiceProvider::class,
                HttpServiceProvider::class,
                TextJsonParserServiceProvider::class
        )
    }
}

fun main(args: Array<String>) {

}
