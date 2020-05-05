package io.kup.framework.container

import kotlin.reflect.KClass

class KupInjector : Injector {
    override fun <T : Any> resolveDependenciesFor(concreteClass: KClass<T>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun <T : Any> dependenciesIn(concreteClass: KClass<T>): List<KClass<*>> {
        val dependencies = ArrayList<KClass<*>>()

        concreteClass.constructors.forEach { constructor ->
            constructor.parameters.forEach { parameter ->
                dependencies.add(parameter.type.classifier as KClass<*>)
            }
        }

        return dependencies
    }
}
