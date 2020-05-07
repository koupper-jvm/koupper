package io.kup.framework.container

class ConcreteClassWithDependencies(val abstractDependency1: AbstractDependency1) : AbstractClass {
    override fun print() {
        print("ConcreteClassWithDependencies")
    }

}
