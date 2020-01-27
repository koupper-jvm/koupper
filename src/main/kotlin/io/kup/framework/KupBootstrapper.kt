package io.kup.framework

open class KupBootstrapper : Bootstrapper {
    init {
        this.go()
    }

    override fun go() {
        println("bootstrapping...")
        print(this.javaClass.`package`)
    }
}
