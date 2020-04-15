package io.kup.framework.container

class KupContainer : Container {
    private var bindings: MutableMap<Any, Any> = mutableMapOf()

    override fun <T: Any> bind(abstractClass: T, callback: () -> T) {
        this.bindings[abstractClass] = callback
    }

    override fun create(): MutableMap<Any, Any> {
        return this.bindings
    }
}
