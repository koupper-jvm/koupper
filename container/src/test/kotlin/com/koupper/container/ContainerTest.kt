package com.koupper.container

import com.koupper.container.exceptions.BindingException
import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.scope.*
import com.koupper.container.exceptions.MultipleAbstractImplementationsException
import com.koupper.container.extensions.instanceOf
import com.koupper.container.extensions.singletonOf
import kotlin.test.*

class ContainerTest : AnnotationSpec() {
    @Test
    fun `should bind a concrete class and return new one instances for multiple creations`() {
        val container = KupContainer()

        container.bind(AbstractClass::class, {
            ConcreteClass()
        })

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
    fun `should bind a singleton class and return the same one instance over and over`() {
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
    fun `should listen for a resolved instance using bind method`() {
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
    fun `should listen for a resolved instance using singleton method`() {
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
    fun `should create a new instance from a full namespace`() {
        val container = KupContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        val concreteClass = container.create().instanceOf("com.koupper.container.AbstractClass") as AbstractClass

        assertTrue {
            concreteClass is ConcreteClass
        }
    }

    @Test
    fun `should create a new instance using its simple class name`() {
        val container = KupContainer()

        container.bind(AbstractClass::class, ConcreteClass::class)

        val concreteClass = container.create().instanceOf("AbstractClass") as AbstractClass

        assertTrue {
            concreteClass is ConcreteClass
        }
    }

    @Test
    fun `should auto bind an abstract class to existing concrete classes in the specified scope`() {
        val container = KupContainer("com.koupper.container.scope")

        val concreteClass = container.create().instanceOf<SingleAbstract>()

        assertTrue {
            concreteClass is SingleConcrete
        }
    }

    @Test
    fun `should throw exception if try create a instance of an abstract class with multiple concrete classes`() {
        val exception = assertFailsWith<MultipleAbstractImplementationsException> {
            KupContainer("com.koupper.container.scope").create().instanceOf<com.koupper.container.scope.AbstractClass>()
        }

        assertTrue {
            exception.cause is MultipleAbstractImplementationsException
            "Type[AbstractClass] has multiple instances" == exception.message
        }
    }

    @Test
    fun `should solve a instance with its dependencies resolved automatically`() {
        val parentConcreteClass = KupContainer("com.koupper.container.scope").create().instanceOf<ParentAbstractClass>()

        assertTrue {
            parentConcreteClass is ParentConcreteClass
            (parentConcreteClass as ParentConcreteClass).firstAbstractClass is FirstConcreteClass
            ((parentConcreteClass).firstAbstractClass as FirstConcreteClass).thirdAbstractClass is ThirdConcreteClass
        }
    }

    @Test
    fun `should bind a concrete class resolving their nested dependencies using the container`() {
        val container = KupContainer("com.koupper.container.scope")

        val parentConcreteClass = ParentConcreteClass::class

        container.bind(ParentAbstractClass::class, parentConcreteClass)

        val resolvedParentConcreteClass = container.create().instanceOf<ParentAbstractClass>()

        assertTrue {
            resolvedParentConcreteClass is ParentConcreteClass
            (resolvedParentConcreteClass as ParentConcreteClass).firstAbstractClass is FirstConcreteClass
            ((resolvedParentConcreteClass).firstAbstractClass as FirstConcreteClass).thirdAbstractClass is ThirdConcreteClass
        }
    }

    @Test
    fun `should throw exception if multiple instances try to be binding to same abstract class`() {
        val container = KupContainer()

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
        val container = KupContainer()

        container.bind(AbstractClass::class, {
            ConcreteClass()
        }, "ConcreteClass")

        container.bind(AbstractClass::class, {
            ConcreteClass2()
        }, "ConcreteClass2")

        assertTrue {
            container.create(tagName = "ConcreteClass").instanceOf<AbstractClass>() is ConcreteClass
            container.create(tagName = "ConcreteClass2").instanceOf<AbstractClass>() is ConcreteClass2
        }
    }

    @Test
    fun `should throw exception if try create a instance of an unbinding class`() {
        val exception = assertFailsWith<BindingException> {
            KupContainer().create().instanceOf<AbstractClass>()
        }

        assertTrue {
            exception.cause is BindingException
            "Type[class com.koupper.container.AbstractClass] is not bound in the container" == exception.message
        }
    }
}
