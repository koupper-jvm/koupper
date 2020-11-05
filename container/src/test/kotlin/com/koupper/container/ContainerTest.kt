package com.koupper.container

import com.koupper.container.exceptions.BindingException
import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.scope.*
import com.koupper.container.exceptions.MultipleAbstractImplementationsException
import com.koupper.container.extensions.get
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.*

class ContainerTest : AnnotationSpec() {
    @Test
    fun `should bind a concrete class and return new one instances for multiple creations`() {
        val container = KoupperContainer()

        container.bind(AbstractClass::class, {
            ConcreteClass()
        })

        val concreteClassOfContainer = container.createInstanceOf(AbstractClass::class)

        assertTrue {
            concreteClassOfContainer is ConcreteClass
        }

        val concreteClassOfContainer2 = container.createInstanceOf(AbstractClass::class)

        assertNotEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should bind a concrete class using the alternative bind method`() {
        val container = KoupperContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        val concreteClassOfContainer = container.createInstanceOf(AbstractClass::class)

        assertTrue {
            concreteClassOfContainer is ConcreteClass
        }

        val concreteClassOfContainer2 = container.createInstanceOf(AbstractClass::class)

        assertNotEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should bind a singleton class and return the same one instance over and over`() {
        val container = KoupperContainer()

        container.singleton(AbstractClass::class) {
            ConcreteClass()
        }

        val concreteClassOfContainer = container.get().createSingletonOf(AbstractClass::class)

        val concreteClassOfContainer2 = container.get().createSingletonOf(AbstractClass::class)

        assertEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should bind a singleton class using the alternative singleton method`() {
        val container = KoupperContainer()

        container.singleton(AbstractClass::class, ConcreteClass::class)

        val concreteClassOfContainer = container.get().createSingletonOf(AbstractClass::class)

        val concreteClassOfContainer2 = container.get().createSingletonOf(AbstractClass::class)

        assertEquals(concreteClassOfContainer, concreteClassOfContainer2)
    }

    @Test
    fun `should listen for a resolved instance using bind method`() {
        val container = KoupperContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        container.listenFor(AbstractClass::class, {
            assertTrue {
                it is ConcreteClass
            }
        })

        container.createInstanceOf(AbstractClass::class)
    }

    @Test
    fun `should listen for a resolved instance using singleton method`() {
        val container = KoupperContainer()

        container.singleton(AbstractClass::class, ConcreteClass::class)

        container.listenFor(AbstractClass::class, {
            assertTrue {
                it is ConcreteClass
            }
        })

        container.createSingletonOf(AbstractClass::class)
    }

    @Test
    fun `should create a new instance from a full namespace`() {
        val container = KoupperContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        val concreteClass = container.createInstanceOf("com.koupper.container.AbstractClass") as AbstractClass

        assertTrue {
            concreteClass is ConcreteClass
        }
    }

    @Test
    fun `should create a new instance using its simple class name`() {
        val container = KoupperContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        val concreteClass = container.createInstanceOf("AbstractClass") as AbstractClass

        assertTrue {
            concreteClass is ConcreteClass
        }
    }

    @Test
    fun `should auto bind an abstract class to existing concrete classes in the specified scope`() {
        val container = KoupperContainer("com.koupper.container.scope")

        val concreteClass = container.createInstanceOf(SingleAbstract::class)

        assertTrue {
            concreteClass is SingleConcrete
        }
    }

    @Test
    fun `should throw exception if try create a instance of an abstract class with multiple concrete classes`() {
        val exception = assertFailsWith<MultipleAbstractImplementationsException> {
            KoupperContainer("com.koupper.container.scope").createInstanceOf(com.koupper.container.scope.AbstractClass::class)
        }

        assertTrue {
            exception.cause is MultipleAbstractImplementationsException
            "Type[AbstractClass] has multiple instances" == exception.message
        }
    }

    @Test
    fun `should solve a instance with its dependencies resolved automatically`() {
        val parentConcreteClass = KoupperContainer("com.koupper.container.scope").createInstanceOf(ParentAbstractClass::class)

        assertTrue {
            parentConcreteClass is ParentConcreteClass
            (parentConcreteClass as ParentConcreteClass).firstAbstractClass is FirstConcreteClass
            ((parentConcreteClass).firstAbstractClass as FirstConcreteClass).thirdAbstractClass is ThirdConcreteClass
        }
    }

    @Test
    fun `should bind a concrete class resolving their nested dependencies using the container`() {
        val container = KoupperContainer("com.koupper.container.scope")

        val parentConcreteClass = ParentConcreteClass::class

        container.bind(ParentAbstractClass::class, parentConcreteClass)

        val resolvedParentConcreteClass = container.createInstanceOf(ParentAbstractClass::class)

        assertTrue {
            resolvedParentConcreteClass is ParentConcreteClass
            (resolvedParentConcreteClass as ParentConcreteClass).firstAbstractClass is FirstConcreteClass
            ((resolvedParentConcreteClass).firstAbstractClass as FirstConcreteClass).thirdAbstractClass is ThirdConcreteClass
        }
    }

    @Test
    fun `should throw exception if multiple instances try to be binding to same abstract class`() {
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
            "Type[AbstractClass] has multiple instances, use tag for exclude the instance." == exception.message
        }
    }

    @Test
    fun `should bind multiple instances to same abstract class`() {
        val container = KoupperContainer()

        container.bind(AbstractClass::class, {
            ConcreteClass()
        }, "ConcreteClass")

        container.bind(AbstractClass::class, {
            ConcreteClass2()
        }, "ConcreteClass2")

        assertTrue {
            container.createInstanceOf(AbstractClass::class, tagName = "ConcreteClass") is ConcreteClass
            container.createInstanceOf(AbstractClass::class, tagName = "ConcreteClass2") is ConcreteClass2
        }
    }

    @Test
    fun `should throw exception if try create a instance of an unbinding class`() {
        val exception = assertFailsWith<BindingException> {
            KoupperContainer().createInstanceOf(AbstractClass::class)
        }

        assertTrue {
            exception.cause is BindingException
            "Type[class com.koupper.container.AbstractClass] is not bound in the container" == exception.message
        }
    }

    @Test
    fun `should bind a generic interface with a generic type`() {
        val container = KoupperContainer()

        class Example {}

        container.bind(GenericAbstractClass::class, {
            GenericConcreteClass<Any>()
        })

        val concreteClassOfContainer = container.createInstanceOf(GenericAbstractClass::class)

        // this assert is unnecessary but it's used to identify the "toType" usage
        assertTrue {
            (concreteClassOfContainer as GenericConcreteClass<*>).toType<Example>() is Example
        }
    }
}
