package com.koupper.providers.templates

import com.koupper.container.app
import com.koupper.providers.ServiceProvider
import com.koupper.providers.templates.loader.TemplateLoader

class TemplateServiceProvider : ServiceProvider() {
    override fun up() {
        registerTemplateStack()
    }

    private fun registerTemplateStack() {
        val config = TemplateConfig.fromEnvironment()
        val loader = TemplateLoaders.create(config)

        // Capture loader instance so classpath/S3 (+ optional cache) stay stable for the process.
        app.bind(TemplateLoader::class, { loader })
        app.bind(TemplateProvider::class, {
            PebbleTemplateProvider(app.getInstance(TemplateLoader::class))
        })
    }

    override fun externalDependencies(): List<String> = listOf(
        // Used when TEMPLATES_DRIVER=s3 (also present via aws-s3 / root BOM).
        "software.amazon.awssdk:s3:2.30.19"
    )
}
