package io.kup.framework.container

interface Container {
    fun <T: Any> bind(abstractClass: T, callback: () -> T)

    fun create() : MutableMap<Any, Any>

    fun <T: Any> singleton(abstractClass: T, callback: () -> T)

    fun get() : MutableMap<Any, Any>
}
