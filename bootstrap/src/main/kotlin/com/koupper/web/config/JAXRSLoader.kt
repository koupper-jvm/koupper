package com.koupper.web.config

import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import org.glassfish.jersey.server.ResourceConfig

class JAXRSLoader : ResourceConfig() {
    fun scanTo(sourcePackage: String) {
        print("Scanning package... $ANSI_GREEN_155$sourcePackage")

        packages(sourcePackage)
    }
}
