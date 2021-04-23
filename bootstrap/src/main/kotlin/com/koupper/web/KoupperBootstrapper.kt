package com.koupper.web

import com.koupper.web.config.JAXRSLoader

open class KoupperBootstrapper : Bootstrapper {
    init {
        this.registerJAXRSComponents()
    }

    override fun registerJAXRSComponents() {
        val sourcePackage = this.javaClass.`package`.clearName()

        JAXRSLoader().scanTo(sourcePackage)
    }
}
