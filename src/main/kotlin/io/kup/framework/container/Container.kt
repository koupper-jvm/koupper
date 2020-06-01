package io.kup.framework.container

import kotlin.reflect.KClass

interface Container {
    fun <T : Any> bind(abstractClass: T, callback: (container: Container) -> T)

    fun <T : Any, V : Any> bind(abstractClass: T, concreteClass: V)

    fun create(): Container

    fun <T : Any> singleton(abstractClass: T, callback: () -> T)

    fun <T : Any, V : Any> singleton(abstractClass: T, concreteClass: V)

    fun get(): Container

    fun <T : Any> listenFor(abstractClass: T, callback: (instance: Any) -> Unit)

    fun getBindings(): MutableMap<Any, Any>

    fun getSingletons(): MutableMap<Any, Any>

    fun getListeners(): MutableMap<Any, Any>

    fun loadAbstractClassesFromScope()
}
