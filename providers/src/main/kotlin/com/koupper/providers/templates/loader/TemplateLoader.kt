package com.koupper.providers.templates.loader

/**
 * Strategy for reading raw template text (HTML) before variable rendering.
 */
fun interface TemplateLoader {
    /**
     * @param path logical template path (e.g. `emails/notify.html` or an S3 object key suffix)
     * @return template content as UTF-8 text
     */
    fun read(path: String): String
}
