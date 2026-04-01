package com.koupper.container

class GenericConcreteClass<T> : GenericAbstractClass<T> {
    private lateinit var value: String

    override fun load(value: String) {
        this.value = value
    }

    inline fun <reified T> toType(): T {
        val concreteClass = T::class

        return concreteClass.constructors.elementAt(0).call()
    }
}