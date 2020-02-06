package io.kup.framework.config

import io.kup.framework.ANSIColors.ANSI_GREEN_155
import org.glassfish.jersey.server.ResourceConfig

class PackageLoader : ResourceConfig() {
    fun include(sourcePackage: String) {
        print("Scanning package... $ANSI_GREEN_155$sourcePackage")

        packages(sourcePackage)
    }
}
