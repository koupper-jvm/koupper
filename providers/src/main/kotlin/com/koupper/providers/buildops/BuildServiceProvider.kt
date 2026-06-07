package com.koupper.providers.buildops

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class BuildServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(BuildProvider::class, { BuildProviderImpl() })
    }

    override fun topLevelFunctions(): Map<String, String> = mapOf(
        "build" to """
            import com.koupper.providers.buildops.BuildProvider
            fun build(): BuildProvider = com.koupper.container.app.getInstance(BuildProvider::class)
        """.trimIndent()
    )
}
