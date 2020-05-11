package io.kup.framework.container

import io.kup.framework.exceptions.ParameterNotInjextedException
import io.kup.framework.extensions.instanceOf
import kotlin.reflect.KClass

val injector: Injector = KupInjector()

class KupInjector : Injector {
    override fun <T : Any> resolveDependenciesFor(concreteClass: KClass<T>): T {
        val parametersOfConstructor = arrayListOf<T>()

        concreteClass.constructors.forEach { constructor ->
            constructor.parameters.forEach { parameter ->
                val type = parameter.type.classifier as KClass<T>

                if (app.getBindings()[type] === null) {
                    throw ParameterNotInjextedException("Type[${type.simpleName}] is not bound in the container")
                }

                if (type.isAbstract) {
                    parametersOfConstructor.add(app.create().instanceOf(type))
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
