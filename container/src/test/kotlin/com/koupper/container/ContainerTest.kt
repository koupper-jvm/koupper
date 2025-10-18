package com.koupper.container

import com.koupper.container.exceptions.BindingException
import com.koupper.container.exceptions.MultipleAbstractImplementationsException
import com.koupper.container.scope.*
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.*

class ContainerTest : AnnotationSpec() {
    @Test
    fun `should throw an exception when trying to create an instance of an abstract class with multiple concrete implementations`() {
        val exception = assertFailsWith<MultipleAbstractImplementationsException> {
            val container = KoupperContainer("com.koupper.container.scope")
            container.bind(com.koupper.container.scope.AbstractClass::class, {
                ConcreteClass()
            })
        }

        assertTrue {
            exception.cause is MultipleAbstractImplementationsException
            "Type[${(AbstractClass::class).simpleName}] exist in the container." == exception.message
        }
    }

    @Test
    fun `should throw an exception when multiple implementations are bound to the same abstract class`() {
        val container = KoupperContainer()

        container.bind(AbstractClass::class, {
            ConcreteClass()
        })

        val exception = assertFailsWith<MultipleAbstractImplementationsException> {
            container.bind(AbstractClass::class, {
                ConcreteClass2()
            })
        }

        assertTrue {
            exception.cause is MultipleAbstractImplementationsException
            "Type[${(AbstractClass::class).simpleName}] exist in the container." == exception.message
        }
    }

    @Test
    fun `should listen for a resolved instance created using the singleton method`() {
        val container = KoupperContainer()

        container.singleton2(AbstractClass::class, ConcreteClass::class)

        container.listenFor(AbstractClass::class, {
            assertTrue {
                it is ConcreteClass
            }
        })

        container.createSingleton(AbstractClass::class)
    }

    @Test
    fun `should create a new instance using its full namespace`() {
        val container = KoupperContainer()

        container.bind2(AbstractClass::class, ConcreteClass::class)

        val concreteClass = container.getInstance("com.koupper.container.AbstractClass") as AbstractClass

        assertTrue {
            concreteClass is ConcreteClass
        }
    }

    @Test
    fun `should bind a singleton class using an alternative singleton method`() {
        val container = KoupperContainer()

        container.singleton2(AbstractClass::class, ConcreteClass::class)

        val concreteClassOfContainer = container.get().createSingleton(AbstractClass::class)

        val concreteClassOfContainer2 = container.get().createSingleton(AbstractClass::class)

        assertEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should bind a singleton class and return the same instance on every creation`() {
        val container = KoupperContainer()

        container.singleton(AbstractClass::class, {
            ConcreteClass()
        })

        val concreteClassOfContainer = container.get().createSingleton(AbstractClass::class)

        val concreteClassOfContainer2 = container.get().createSingleton(AbstractClass::class)

        assertEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should automatically bind an abstract class to its existent concrete class in the specified scope`() {
        val container = KoupperContainer("com.koupper.container.scope")

        val concreteClass = container.getInstance(SingleAbstract::class)

        assertTrue {
            concreteClass is SingleConcrete
        }
    }

    @Test
    fun `should bind a concrete class and return a new instance on every creation`() {
        val container = KoupperContainer()

        container.bind(AbstractClass::class, {
            ConcreteClass()
        })

        val concreteClassOfContainer = container.getInstance(AbstractClass::class)

        assertTrue {
            concreteClassOfContainer is ConcreteClass
        }

        val concreteClassOfContainer2 = container.getInstance(AbstractClass::class)

        assertNotEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should bind a concrete class using an alternative bind method`() {
        val container = KoupperContainer()

        container.bind2(AbstractClass::class, ConcreteClass::class)

        val concreteClassOfContainer = container.getInstance(AbstractClass::class)

        assertTrue {
            concreteClassOfContainer is ConcreteClass
        }

        val concreteClassOfContainer2 = container.getInstance(AbstractClass::class)

        assertNotEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should throw an exception when trying to bind a preloaded class`() {
        val exception = assertFailsWith<MultipleAbstractImplementationsException> {
            val container = KoupperContainer("com.koupper.container.scope")

            val parentConcreteClass = ParentConcreteClass::class

            container.bind2(ParentAbstractClass::class, parentConcreteClass)
        }

        assertTrue {
            exception.cause is MultipleAbstractImplementationsException
            "Type[ParentAbstractClass] exist in the container." == exception.message
        }
    }

    @Test
    fun `should listen for a resolved instance created using the bind method`() {
        val container = KoupperContainer()

        container.bind2(AbstractClass::class, ConcreteClass::class)

        container.listenFor(AbstractClass::class, {
            assertTrue {
                it is ConcreteClass
            }
        })

        container.getInstance(AbstractClass::class)
    }

    @Test
    fun `should create an instance with its dependencies automatically resolved`() {
        val parentConcreteClass = KoupperContainer("com.koupper.container.scope").getInstance(ParentAbstractClass::class)

        assertTrue {
            parentConcreteClass is ParentConcreteClass
            (parentConcreteClass as ParentConcreteClass).firstAbstractClass is FirstConcreteClass
            ((parentConcreteClass).firstAbstractClass as FirstConcreteClass).thirdAbstractClass is ThirdConcreteClass
        }
    }

    @Test
    fun `should bind multiple instances to the same abstract class`() {
        val container = KoupperContainer()

        container.bind(AbstractClass::class, {
            ConcreteClass()
        }, "ConcreteClass")

        container.bind(AbstractClass::class, {
            ConcreteClass2()
        }, "ConcreteClass2")

        assertTrue {
            container.getInstance(AbstractClass::class, tagName = "ConcreteClass") is ConcreteClass
            container.getInstance(AbstractClass::class, tagName = "ConcreteClass2") is ConcreteClass2
        }
    }

    @Test
    fun `should throw an exception when trying to create an instance of unbound class`() {
        val exception = assertFailsWith<BindingException> {
            KoupperContainer().getInstance(AbstractClass::class)
        }

        assertTrue {
            exception.cause is BindingException
            "Type[class com.koupper.container.AbstractClass] is not bound in the container" == exception.message
        }
    }

    @Test
    fun `should bind a generic interface to a generic type`() {
        val container = KoupperContainer()

        abstract class GenericAbstractClass<T>
        class GenericConcreteClass<T> : GenericAbstractClass<T>()

        class Example

        container.bind(GenericAbstractClass::class, {
            GenericConcreteClass<Example>()
        })

        val instance = container.getInstance(GenericAbstractClass::class)

        assertIs<GenericConcreteClass<Example>>(instance)

        assertTrue(instance is GenericConcreteClass<*>)
        assertTrue(instance is GenericAbstractClass<*>)
    }
}
