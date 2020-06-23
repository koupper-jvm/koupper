package io.kup.container

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.container.exceptions.ParameterNotInjectedException
import io.kup.container.extensions.instanceOf
import io.kup.container.injector.KupInjector
import io.kup.container.injector.injector
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InjectorTest : AnnotationSpec() {
    @Test
    fun `should get a list of dependencies for specific object`() {
        val injector = KupInjector()

        val dependencies = injector.listDependenciesIn(ConcreteClassWithDependencies::class)

        assertEquals("io.kup.container.AbstractDependency1", dependencies[0].qualifiedName)
    }

    @Test
    fun `should inject dependencies for a certain object binding in the container`() {
        val container = app

        container.bind(AbstractNestedDependency2::class) {
            ConcreteNestedDependency2()
        }

        container.bind(AbstractDependency1::class) {
            ConcreteDependency1(it.create().instanceOf())
        }

        val injector = KupInjector()

        val instance = injector.resolveDependenciesFor(container, ConcreteClassWithDependencies::class)

        assertTrue {
            instance.hasInjectedDependencies()
            instance.abstractDependency1.hasInjectedDependencies()
        }
    }

    @Test
    fun `should not inject dependencies for unbinding object in container`() {
        val exception = assertFailsWith<ParameterNotInjectedException> {
            injector.resolveDependenciesFor(KupContainer(), ConcreteClassWithDependencies::class)
        }

        assertTrue {
            exception.cause is ParameterNotInjectedException
            "Type[AbstractDependency1] is not bound in the container" == exception.message
        }
    }
}
