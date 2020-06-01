package io.kup.framework.container

import kotlin.reflect.KClass

interface Injector {
    fun <T : Any> resolveDependenciesFor(container: Container, concreteClass: KClass<T>): T

    fun <T : Any> listDependenciesIn(concreteClass: KClass<T>): List<KClass<*>>
}
