package io.kup.framework.container

class KupContainer : Container {
    private var bindings: MutableMap<Any, Any> = mutableMapOf()

    private var singletons: MutableMap<Any, Any> = mutableMapOf()

    override fun <T: Any> bind(abstractClass: T, callback: () -> T) {
        this.bindings[abstractClass] = callback
    }

    override fun create(): MutableMap<Any, Any> {
        return this.bindings
    }

    override fun <T : Any> singleton(abstractClass: T, callback: () -> T) {
        this.singletons[abstractClass] = callback.invoke()
    }

    override fun get(): MutableMap<Any, Any> {
        return this.singletons
    }
}
