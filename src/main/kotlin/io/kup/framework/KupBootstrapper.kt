package io.kup.framework

import io.kup.framework.config.PackageLoader

open class KupBootstrapper : Bootstrapper {
    init {
        this.go()
    }

    override fun go() {
        val sourcePackage = this.javaClass.`package`.clearName()

        PackageLoader().include(sourcePackage)
    }
}
