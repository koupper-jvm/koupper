package io.kup.container.injector

import io.kup.container.interfaces.Container
import kotlin.reflect.KClass

interface Injector {
    fun <T : Any> resolveDependenciesFor(container: Container, concreteClass: KClass<T>): T

    fun <T : Any> listDependenciesIn(concreteClass: KClass<T>): List<KClass<*>>
}
