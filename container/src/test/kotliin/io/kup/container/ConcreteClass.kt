package io.kup.container

class ConcreteClass : AbstractClass {
    override fun print() {
        print("Hello developer!")
    }

    override fun hasInjectedDependencies() = false
}
