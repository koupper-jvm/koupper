package com.koupper.container

class ConcreteDependency1(private val abstractNestedDependency2: AbstractNestedDependency2) : AbstractDependency1 {
    override fun exist() = true

    override fun print() {
        print("ConcreteDependency1")
    }

    override fun hasInjectedDependencies(): Boolean {
        return if (this.abstractNestedDependency2 != null) this.abstractNestedDependency2.exist() else false
    }
}
