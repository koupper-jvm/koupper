package com.koupper.framework

import com.koupper.framework.config.JAXRSLoader

open class KoupperBootstrapper : Bootstrapper {
    init {
        this.registerJAXRSComponents()
    }

    override fun registerJAXRSComponents() {
        val sourcePackage = this.javaClass.`package`.clearName()

        JAXRSLoader().scanTo(sourcePackage)
    }
}
