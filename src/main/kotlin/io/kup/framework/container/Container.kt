package io.kup.framework.container

interface Container {
    fun <T : Any> bind(abstractClass: T, callback: () -> T)

    fun <T : Any, V : Any> bind(abstractClass: T, concreteClass: V)

    fun create(): MutableMap<Any, Any>

    fun <T : Any> singleton(abstractClass: T, callback: () -> T)

    fun <T : Any, V : Any> singleton(abstractClass: T, concreteClass: V)

    fun get(): MutableMap<Any, Any>
}
