package com.koupper.providers

import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.despatch.SenderServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
import com.koupper.providers.parsing.TextParserServiceProvider
import kotlin.reflect.KClass

class ServiceProviderManager {
    fun listProviders(): List<KClass<*>> {
        return listOf(
                DBServiceProvider::class,
                SenderServiceProvider::class,
                TextParserServiceProvider::class,
                LoggerServiceProvider::class
        )
    }
}

fun main(args: Array<String>) {

}
