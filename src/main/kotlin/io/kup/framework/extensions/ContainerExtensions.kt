package io.kup.framework.extensions

import io.kup.framework.container.Container
import io.kup.framework.container.KupContainer

inline fun <reified T> Container.instanceOf(): T {
    val instance = if (this.getBindings()[T::class] is Function<*>) {
        (this.getBindings()[T::class] as () -> T).invoke()
    } else {
        this.getBindings()[T::class] as T
    }

    if (this.getListeners()[T::class] is Function<*>) {
        val callback = this.getListeners()[T::class] as (T) -> Unit

        callback(instance)
    }

    return instance
}

inline fun <reified T> KupContainer.singletonOf(): T {
    val instance = this.getSingletons()[T::class] as T

    if (this.getListeners()[T::class] is Function<*>) {
        val callback = this.getListeners()[T::class] as (T) -> Unit

        callback(instance)
    }

    return instance
}
