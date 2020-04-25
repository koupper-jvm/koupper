package io.kup.framework.container

import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

class KupContainer : Container {
    private var bindings: MutableMap<Any, Any> = mutableMapOf()

    private var singletons: MutableMap<Any, Any> = mutableMapOf()

    private var listeners: MutableMap<Any, Any> = mutableMapOf()

    override fun <T : Any> bind(abstractClass: T, callback: () -> T) {
        this.bindings[abstractClass] = callback
    }

    override fun <T : Any, V : Any> bind(abstractClass: T, concreteClass: V) {
        this.bindings[abstractClass] = { (concreteClass as KClass<*>).createInstance() }
    }

    override fun create(): KupContainer {
        return this
    }

    override fun <T : Any> singleton(abstractClass: T, callback: () -> T) {
        this.singletons[abstractClass] = callback.invoke()
    }

    override fun <T : Any, V : Any> singleton(abstractClass: T, concreteClass: V) {
        this.singletons[abstractClass] = (concreteClass as KClass<*>).createInstance()
    }

    override fun get(): KupContainer {
        return this
    }

    override fun <T : Any> listenFor(abstractClass: T, callback: (instance: Any) -> Unit) {
        this.listeners[abstractClass] = callback
    }

    override fun getBindings(): MutableMap<Any, Any> {
        return this.bindings
    }

    override fun getSingletons(): MutableMap<Any, Any> {
        return this.singletons
    }

    override fun getListeners(): MutableMap<Any, Any> {
        return this.listeners
    }
}
