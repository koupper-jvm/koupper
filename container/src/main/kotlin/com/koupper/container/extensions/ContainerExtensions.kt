package com.koupper.container.extensions

import com.koupper.container.interfaces.Container
import com.koupper.container.KupContainer
import com.koupper.container.exceptions.BindingException
import com.koupper.container.injector.injector
import com.koupper.container.exceptions.MultipleAbstractImplementationsException
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
        container.getBindings()[kClass] is List<*> -> {
            if (container.getBindings()[kClass] != null && (container.getBindings()[kClass] as List<*>).size > 1) {
                throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
            }

            injector.resolveDependenciesFor(container, (container.getBindings()[kClass] as List<*>)[0] as KClass<*>) as T
        }
        container.getBindings()[kClass.java] is List<*> -> {
            if (container.getBindings()[kClass.java] != null && (container.getBindings()[kClass.java] as List<*>).size > 1) {
                throw MultipleAbstractImplementationsException("Type[${kClass.simpleName}] has multiple instances")
            }

            injector.resolveDependenciesFor(container, (container.getBindings()[kClass.java] as List<*>)[0] as KClass<*>) as T
        }
        container.getBindings()[kClass] == null -> {
            throw BindingException("Type[$kClass] is not bound in the container")
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
