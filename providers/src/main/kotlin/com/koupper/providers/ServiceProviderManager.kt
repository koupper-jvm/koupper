package com.koupper.providers

import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.dispatch.SenderServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
import com.koupper.providers.parsing.TextParserServiceProvider
import kotlin.reflect.KClass

class ServiceProviderManager {
    fun listProviders(): List<KClass<*>> {
        return listOf(
                TextParserServiceProvider::class,
                DBServiceProvider::class,
                SenderServiceProvider::class,
                LoggerServiceProvider::class
        )
    }
}

fun main(args: Array<String>) {

}
