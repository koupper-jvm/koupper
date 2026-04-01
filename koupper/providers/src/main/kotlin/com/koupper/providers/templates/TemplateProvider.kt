package com.koupper.providers.templates

/**
 * Contract for any HTML template loader and processor.
 *
 * Implementations should be able to:
 * - Load templates from either classpath resources or absolute filesystem paths.
 * - Replace variables in the format `${key}` within the template.
 * - Optionally extract only the `<body>` content (useful for email rendering).
 */
interface TemplateProvider {

    /**
     * Loads an HTML template and applies variable substitutions.
     *
     * @param path name of the resource (e.g. "emails/notify.html") or an absolute path.
     * @param values map with keys and values to replace in the template.
     * @param fromFile if true, loads from the filesystem; otherwise, from classpath resources.
     * @return the rendered HTML template as a String.
     */
    fun load(path: String, values: Map<String, Any?> = emptyMap(), fromFile: Boolean = false): String

    /**
     * Extracts only the `<body>...</body>` section from the HTML.
     *
     * @param html full HTML content.
     * @return body section or full HTML if no `<body>` tag is found.
     */
    fun extractBody(html: String): String
}
