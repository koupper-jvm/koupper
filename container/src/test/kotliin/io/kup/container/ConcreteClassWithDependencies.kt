package io.kup.container

class ConcreteClassWithDependencies(val abstractDependency1: AbstractDependency1) : AbstractClass {
    override fun print() {
        print("ConcreteClassWithDependencies")
    }

    override fun hasInjectedDependencies(): Boolean {
        return if (this.abstractDependency1 != null) this.abstractDependency1.exist() else false
    }
}
