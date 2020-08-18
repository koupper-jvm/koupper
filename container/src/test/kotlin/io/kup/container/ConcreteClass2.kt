package io.kup.container

class ConcreteClass2: AbstractClass {
    override fun print() {
        print("Hello developer!")
    }

    override fun hasInjectedDependencies() = false
}