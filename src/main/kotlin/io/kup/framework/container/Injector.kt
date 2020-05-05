package io.kup.framework.container

import kotlin.reflect.KClass

interface Injector {
    fun <T : Any> resolveDependenciesFor(concreteClass: KClass<T>)

    fun <T : Any> dependenciesIn(concreteClass: KClass<T>): List<KClass<*>>
}
