package com.koupper.server

import org.glassfish.jersey.server.ResourceConfig

class Application : ResourceConfig() {
    init {
        packages("com.koupper.project.manager")
    }
}
