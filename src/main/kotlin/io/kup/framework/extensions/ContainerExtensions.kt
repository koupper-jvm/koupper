package io.kup.framework.extensions

import io.kup.framework.container.Container
import io.kup.framework.container.KupContainer
import kotlin.reflect.KClass

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

fun <T : Any> Container.instanceOf(kClass: KClass<T>): T {
    val instance = if (this.getBindings()[kClass] is Function<*>) {
        (this.getBindings()[kClass] as () -> T).invoke()
    } else {
        this.getBindings()[kClass] as T
    }

    if (this.getListeners()[kClass] is Function<*>) {
        val callback = this.getListeners()[kClass] as (T) -> Unit

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

fun <T> Container.instanceOf(name: String): T {
    var instance: T? = null

    this.getBindings().forEach lit@{ key, value ->
        if ((key as KClass<*>).qualifiedName == name || key.simpleName == name ) {
            instance = if (value is Function<*>) {
                (value as () -> T).invoke()
            } else {
                value as T
            }

            return@lit
        }
    }

    return instance!!
}
