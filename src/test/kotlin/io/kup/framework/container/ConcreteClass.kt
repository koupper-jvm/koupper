package io.kup.framework.container

class ConcreteClass : AbstractClass {
    override fun print() {
        print("Hello developer!")
    }

    override fun hasInjectedDependencies() = false
}
