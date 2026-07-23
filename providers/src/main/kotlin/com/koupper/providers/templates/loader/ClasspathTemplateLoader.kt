package com.koupper.providers.templates.loader

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Default loader: classpath resource, then `src/main/resources/<path>` fallback (local DX).
 */
class ClasspathTemplateLoader : TemplateLoader {
    override fun read(path: String): String {
        val cl = Thread.currentThread().contextClassLoader
            ?: ClasspathTemplateLoader::class.java.classLoader

        val resource = cl.getResource(path)
        when {
            resource != null -> return resource.readText(StandardCharsets.UTF_8)
            File("src/main/resources/$path").exists() ->
                return File("src/main/resources/$path").readText(StandardCharsets.UTF_8)
            else -> throw IllegalArgumentException(
                "Template not found on classpath or filesystem: $path"
            )
        }
    }
}
