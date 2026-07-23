package com.koupper.providers.templates

/**
 * Contract for HTML template loading and variable rendering.
 *
 * Raw content is resolved via a pluggable [com.koupper.providers.templates.loader.TemplateLoader]
 * (`classpath` by default, optional `s3`). Implementations then apply Pebble / legacy `${key}` substitution.
 */
interface TemplateProvider {

    /**
     * Loads an HTML template and applies variable substitutions.
     *
     * @param path logical template path (e.g. `emails/notify.html`), or absolute path when [fromFile] is true.
     * @param values map with keys and values to replace in the template.
     * @param fromFile if true, loads from the filesystem path; otherwise uses the configured TemplateLoader.
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
