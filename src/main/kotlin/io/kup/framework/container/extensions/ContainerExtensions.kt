package io.kup.framework.container.extensions

import io.kup.framework.container.Container
import io.kup.framework.container.KupContainer
import io.kup.framework.container.injector.injector
import io.kup.framework.container.exceptions.MultipleAbstractImplementationsException
import kotlin.reflect.KClass
import kotlin.reflect.KType

inline fun <reified T : Any> Container.instanceOf(): T {
    return createInstance(this, T::class)
}

inline fun <reified T> KupContainer.singletonOf(): T {
    val instance = this.getSingletons()[T::class] as T

    if (this.getListeners()[T::class] is Function<*>) {
        val callback = this.getListeners()[T::class] as (T) -> Unit

        callback(instance)
    }

    return instance
}

fun <T : Any> Container.instanceOf(kClass: KClass<T>): T {
    return createInstance(this, kClass)
}

fun <T : Any> createInstance(container: Container, kClass: KClass<T>): T {
    val instance = when {
        container.getBindings()[kClass] is Function<*> -> {
            (container.getBindings()[kClass] as () -> T).invoke()
        }
        container.getBindings()[kClass] is ArrayList<*> -> {
            if (container.getBindings()[kClass] != null && (container.getBindings()[kClass] as ArrayList<*>).size > 1) {
                throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
            }

            injector.resolveDependenciesFor(container, (container.getBindings()[kClass] as ArrayList<*>)[0] as KClass<*>) as T
        }
        container.getBindings()[kClass.java] is ArrayList<*> -> {
            if (container.getBindings()[kClass.java] != null && (container.getBindings()[kClass.java] as ArrayList<*>).size > 1) {
                throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
            }

            injector.resolveDependenciesFor(container, (container.getBindings()[kClass.java] as ArrayList<*>)[0] as KClass<*>) as T
        }
        else -> {
            injector.resolveDependenciesFor(container, container.getBindings()[kClass] as KClass<T>)
        }
    }

    if (container.getListeners()[kClass] is Function<*>) {
        val callback = container.getListeners()[kClass] as (T) -> Unit

        callback(instance)
    }

    return instance
}

fun <T : Any> Container.instanceOf(name: String): T {
    var instance: T? = null

    this.getBindings().forEach lit@{ key, value ->
        if ((key as KClass<*>).qualifiedName == name || key.simpleName == name) {
            instance = when (value) {
                is Function<*> -> {
                    (value as () -> T).invoke()
                }
                is KClass<*> -> {
                    injector.resolveDependenciesFor(this, value as KClass<T>)
                }
                else -> {
                    value as T
                }
            }

            return@lit
        }
    }

    return instance!!
}

fun KType.isNotAnyClass(): Boolean {
    return !(this.classifier as KClass<*>).simpleName.equals("Any")
}

fun KClass<*>.isConcrete(): Boolean {
    return !this.isAbstract
}

fun KType.asKClass(): KClass<*> {
    return this.classifier as KClass<*>
}
