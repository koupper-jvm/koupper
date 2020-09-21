package com.koupper.container

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.exceptions.ParameterNotInjectedException
import com.koupper.container.injector.KoupperInjector
import com.koupper.container.injector.injector
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InjectorTest : AnnotationSpec() {
    @Test
    fun `should get a list of dependencies for specific object`() {
        val injector = KoupperInjector()

        val dependencies = injector.listDependenciesIn(ConcreteClassWithDependencies::class)

        assertEquals("com.koupper.container.AbstractDependency1", dependencies[0].qualifiedName)
    }

    @Test
    fun `should inject dependencies for a certain object binding in the container`() {
        val container = app

        container.bind(AbstractNestedDependency2::class, {
            ConcreteNestedDependency2()
        })

        container.bind(AbstractDependency1::class, {
            ConcreteDependency1(it.createInstanceOf(AbstractNestedDependency2::class))
        })

        val injector = KoupperInjector()

        val instance = injector.resolveDependenciesFor(container, ConcreteClassWithDependencies::class)

        assertTrue {
            instance.hasInjectedDependencies()
            instance.abstractDependency1.hasInjectedDependencies()
        }
    }

    @Test
    fun `should not inject dependencies for unbinding object in container`() {
        val exception = assertFailsWith<ParameterNotInjectedException> {
            injector.resolveDependenciesFor(KoupperContainer(), ConcreteClassWithDependencies::class)
        }

        assertTrue {
            exception.cause is ParameterNotInjectedException
            "Type[AbstractDependency1] is not bound in the container." == exception.message
        }
    }
}
