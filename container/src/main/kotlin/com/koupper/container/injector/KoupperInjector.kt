package com.koupper.container.injector

import  com.koupper.container.interfaces.Container
import com.koupper.container.exceptions.ParameterNotInjectedException
import kotlin.reflect.KClass

val injector: Injector = KoupperInjector()

class KoupperInjector : Injector {
    override fun <T : Any> resolveDependenciesFor(container: Container, concreteClass: KClass<T>): T {
        val parametersOfConstructor = arrayListOf<T>()

        concreteClass.constructors.forEach { constructor ->
            constructor.parameters.forEach { parameter ->
                val type = parameter.type.classifier as KClass<T>

                if (container.getBindings()[type] === null && container.getBindings()[type.java] === null) {
                    throw ParameterNotInjectedException("Type[${type.simpleName}] is not bound in the container.")
                }

                if (type.isAbstract) {
                    parametersOfConstructor.add(container.getInstance(type))
                }
            }
        }

        return concreteClass.constructors.elementAt(0).call(*parametersOfConstructor.toArray())
    }

    override fun <T : Any> listDependenciesIn(concreteClass: KClass<T>): List<KClass<*>> {
        val dependencies = ArrayList<KClass<*>>()

        concreteClass.constructors.forEach { constructor ->
            constructor.parameters.forEach { parameter ->
                dependencies.add(parameter.type.classifier as KClass<*>)
            }
        }

        return dependencies
    }
}
