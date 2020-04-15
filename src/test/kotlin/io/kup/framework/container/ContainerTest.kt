package io.kup.framework.container

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.framework.extensions.instance
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContainerTest : AnnotationSpec() {
    @Test
    fun `should bind a concrete class and return new instances for multiple creations`() {
        val container = KupContainer()

        container.bind(AbstractClass::class) {
            ConcreteClass()
        }

        val concreteClassOfContainer = container.create().instance<AbstractClass>()

        assertTrue(concreteClassOfContainer is ConcreteClass)

        val concreteClassOfContainer2 = container.create().instance<AbstractClass>()

        assertNotEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }
}
