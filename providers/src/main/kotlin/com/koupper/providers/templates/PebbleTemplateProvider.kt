package com.koupper.providers.templates

import com.koupper.providers.templates.loader.ClasspathTemplateLoader
import com.koupper.providers.templates.loader.TemplateLoader
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.StringLoader
import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets

/**
 * Pebble-based template provider.
 *
 * Raw HTML is obtained via [TemplateLoader] (classpath by default, or S3 when configured).
 * [fromFile] remains an explicit escape hatch for absolute filesystem paths.
 */
class PebbleTemplateProvider(
    private val loader: TemplateLoader = ClasspathTemplateLoader()
) : TemplateProvider {

    override fun load(path: String, values: Map<String, Any?>, fromFile: Boolean): String {
        val htmlText: String = if (fromFile) {
            File(path).readText(StandardCharsets.UTF_8)
        } else {
            loader.read(path)
        }

        return try {
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
            applyLegacyPlaceholders(htmlText, values)
        }
    }

    private fun applyLegacyPlaceholders(template: String, values: Map<String, Any?>): String {
        return values.entries.fold(template) { acc, (key, value) ->
            acc.replace("\${$key}", value?.toString() ?: "")
        }
    }

    override fun extractBody(html: String): String {
        val bodyRegex = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
        return bodyRegex.find(html)?.groupValues?.get(1)?.trim() ?: html
    }
}
