package io.kup.framework

import io.kup.framework.config.JAXRSLoader

open class KupBootstrapper : Bootstrapper {
    init {
        this.registerJAXRSComponents()
    }

    override fun registerJAXRSComponents() {
        val sourcePackage = this.javaClass.`package`.clearName()

        JAXRSLoader().scanTo(sourcePackage)
    }
}
