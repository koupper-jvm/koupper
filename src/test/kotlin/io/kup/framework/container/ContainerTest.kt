package io.kup.framework.container

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.framework.extensions.instanceOf
import io.kup.framework.extensions.singletonOf
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContainerTest : AnnotationSpec() {
    @Test
    fun `should bind a concrete class and return new instances for multiple creations`() {
        val container = KupContainer()

        container.bind(AbstractClass::class) {
            ConcreteClass()
        }

        val concreteClassOfContainer = container.create().instanceOf<AbstractClass>()

        assertTrue {
            concreteClassOfContainer is ConcreteClass
        }

        val concreteClassOfContainer2 = container.create().instanceOf<AbstractClass>()

        assertNotEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should bind a concrete class using the alternative bind method`() {
        val container = KupContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        val concreteClassOfContainer = container.create().instanceOf<AbstractClass>()

        assertTrue {
            concreteClassOfContainer is ConcreteClass
        }

        val concreteClassOfContainer2 = container.create().instanceOf<AbstractClass>()

        assertNotEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should bind a singleton class and return the same instance over and over`() {
        val container = KupContainer()

        container.singleton(AbstractClass::class) {
            ConcreteClass()
        }

        val concreteClassOfContainer = container.get().singletonOf<AbstractClass>()

        val concreteClassOfContainer2 = container.get().singletonOf<AbstractClass>()

        assertEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should bind a singleton class using the alternative singleton method`() {
        val container = KupContainer()

        container.singleton(AbstractClass::class, ConcreteClass::class)

        val concreteClassOfContainer = container.get().singletonOf<AbstractClass>()

        val concreteClassOfContainer2 = container.get().singletonOf<AbstractClass>()

        assertEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should listen for a resolved by bind method`() {
        val container = KupContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        container.listenFor(AbstractClass::class, {
            assertTrue {
                it is ConcreteClass
            }
        })

        container.create().instanceOf<AbstractClass>()
    }

    @Test
    fun `should listen for a resolved by singleton method`() {
        val container = KupContainer()

        container.singleton(AbstractClass::class, ConcreteClass::class)

        container.listenFor(AbstractClass::class, {
            assertTrue {
                it is ConcreteClass
            }
        })

        container.create().singletonOf<AbstractClass>()
    }

    @Test
    fun `should create a new instance from a class package name`() {
        val container = KupContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        val concreteClass = container.create().instanceOf("io.kup.framework.container.AbstractClass") as AbstractClass

        assertTrue {
            concreteClass is ConcreteClass
        }
    }

    @Test
    fun `should create a news instance from a class name`() {
        val container = KupContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        val concreteClass = container.create().instanceOf("AbstractClass") as AbstractClass

        assertTrue {
            concreteClass is ConcreteClass
        }
    }
}
