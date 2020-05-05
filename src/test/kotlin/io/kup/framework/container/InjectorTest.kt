package io.kup.framework.container

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals

class InjectorTest : AnnotationSpec() {
    @Test
    fun `should get a list of dependencies for specific object`() {
        val injector = KupInjector()

        val dependencies = injector.dependenciesIn(ConcreteClassWithDependencies::class)


        assertEquals("AbstractClass", dependencies[0].simpleName)
    }
}
