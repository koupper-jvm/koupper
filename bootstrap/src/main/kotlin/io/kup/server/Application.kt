package io.kup.server

import org.glassfish.jersey.server.ResourceConfig

class Application : ResourceConfig() {
    init {
        packages("io.kup.project.manager")
    }
}
