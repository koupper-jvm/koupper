package com.koupper.providers.templates

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.StringLoader
import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets

/**
 * A Pebble-based template provider that keeps the same resource detection logic
 * from the original TemplateProviderImpl, but renders from the loaded string.
 */
class PebbleTemplateProvider : TemplateProvider {

    override fun load(path: String, values: Map<String, Any?>, fromFile: Boolean): String {
        val htmlText: String = if (fromFile) {
            println("📂 [TemplateProvider] Reading from filesystem: $path")
            File(path).readText(StandardCharsets.UTF_8)
        } else {
            println("🧭 [TemplateProvider] Looking for resource: $path")

            val cl = Thread.currentThread().contextClassLoader ?: PebbleTemplateProvider::class.java.classLoader
            println("📦 ClassLoader: ${cl::class.qualifiedName}")

            val resource = cl.getResource(path)
            println("🔎 getResource('$path') = ${resource ?: "❌ NOT FOUND"}")

            when {
                resource != null -> {
                    println("✅ Resource found: ${resource.file}")
                    resource.readText(StandardCharsets.UTF_8)
                }
                File("src/main/resources/$path").exists() -> {
                    val fallback = File("src/main/resources/$path")
                    println("✅ Found fallback resource: ${fallback.absolutePath}")
                    fallback.readText(StandardCharsets.UTF_8)
                }
                else -> throw IllegalArgumentException("❌ Resource not found in classpath or filesystem: $path")
            }
        }

        return try {
            println("🧩 [TemplateProvider] Rendering with Pebble (StringLoader)")
            val engine = PebbleEngine.Builder()
                .loader(StringLoader())
                .autoEscaping(false)
                .cacheActive(false)
                .build()

            val template = engine.getTemplate(htmlText)
            val writer = StringWriter()
            template.evaluate(writer, values)
            writer.toString()
        } catch (e: Exception) {
            // 🔹 3️⃣ Fallback legacy (${key}) si Pebble falla
            println("⚠️ [TemplateProvider] Pebble failed → applying legacy replace. Reason: ${e.message}")
            applyLegacyPlaceholders(htmlText, values)
        }
    }

    /**
     * Sustitución de variables tipo `${key}`, solo para plantillas antiguas.
     */
    private fun applyLegacyPlaceholders(template: String, values: Map<String, Any?>): String {
        return values.entries.fold(template) { acc, (key, value) ->
            acc.replace("\${$key}", value?.toString() ?: "")
        }
    }

    /**
     * Extrae el contenido dentro del <body> para envío de emails.
     */
    override fun extractBody(html: String): String {
        val bodyRegex = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
        return bodyRegex.find(html)?.groupValues?.get(1)?.trim() ?: html
    }
}
