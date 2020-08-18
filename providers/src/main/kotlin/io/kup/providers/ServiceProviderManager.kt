package io.kup.providers

import io.kup.providers.db.DBServiceProvider
import io.kup.providers.despatch.SenderServiceProvider
import io.kup.providers.parsing.TextParserServiceProvider
import kotlin.reflect.KClass

class ServiceProviderManager {
    fun listProviders(): List<KClass<*>> {
        return listOf(
                DBServiceProvider::class,
                SenderServiceProvider::class,
                TextParserServiceProvider::class
        )
    }
}

fun main(args: Array<String>) {

}
