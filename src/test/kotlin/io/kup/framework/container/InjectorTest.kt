package io.kup.framework.container

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.framework.exceptions.ParameterNotInjextedException
import io.kup.framework.extensions.instanceOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InjectorTest : AnnotationSpec() {
    @Test
    fun `should get a list of dependencies for specific object`() {
        val injector = KupInjector()

        val dependencies = injector.listDependenciesIn(ConcreteClassWithDependencies::class)

        assertEquals("io.kup.framework.container.AbstractDependency1", dependencies[0].qualifiedName)
    }

    @Test
    fun `should resolve dependencies for a certain object`() {
        val container = app

        container.bind(AbstractNestedDependency2::class) {
            ConcreteNestedDependency2()
        }

        container.bind(AbstractDependency1::class) {
            ConcreteDependency1(it.create().instanceOf())
        }

        val injector = KupInjector()

        val instance = injector.resolveDependenciesFor(ConcreteClassWithDependencies::class)

        assertTrue {
            instance.hasInjectedDependencies()
            instance.abstractDependency1.hasInjectedDependencies()
        }
    }

    @Test
    fun `should not resolve dependencies for a no binding object in container`() {
        val exception = assertFailsWith<ParameterNotInjextedException> {
            injector.resolveDependenciesFor(ConcreteClassWithDependencies::class)
        }

        assertTrue {
            exception.cause is ParameterNotInjextedException
            "Type[AbstractDependency1] is not bound in the container" == exception.message
        }
    }
}
