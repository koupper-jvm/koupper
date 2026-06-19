package com.koupper.shared.errors

data class KoupperError(
    val code: String,
    val message: String,
    val details: String? = null,
    val suggestion: String? = null
) {
    override fun toString(): String = buildString {
        append(code)
        append(": ")
        append(message)
        if (details != null) {
            append("\n  details: ")
            append(details)
        }
        if (suggestion != null) {
            append("\n  hint: ")
            append(suggestion)
        }
    }

    companion object {
        fun exportMissing() = KoupperError(
            code = "ERR_EXPORT_MISSING",
            message = "No @Export entrypoint found",
            suggestion = "Add exactly one @Export declaration. Example:\n  @Export\n  val setup: () -> String = { \"hello\" }"
        )

        fun exportMultiple(names: List<String>) = KoupperError(
            code = "ERR_EXPORT_MULTIPLE",
            message = "Multiple @Export declarations found: ${names.joinToString(", ")}",
            suggestion = "Use exactly one @Export entrypoint. Consider renaming the script to be a single-purpose module."
        )

        fun compileError(error: String) = KoupperError(
            code = "ERR_COMPILE",
            message = "Script compilation failed",
            details = error.take(500),
            suggestion = "Check imports, syntax, and provider availability."
        )

        fun versionMismatch(expected: String, actual: String) = KoupperError(
            code = "ERR_VERSION_MISMATCH",
            message = "Koupper version mismatch",
            details = "Script expects v$expected but runtime is v$actual",
            suggestion = "Update the script's @KoupperVersion or downgrade Koupper to match."
        )

        fun runtimeError(error: String) = KoupperError(
            code = "ERR_RUNTIME",
            message = "Script execution failed",
            details = error.take(500)
        )

        fun providerInitFailed(providerName: String, reason: String) = KoupperError(
            code = "ERR_PROVIDER_INIT",
            message = "Provider initialization failed: $providerName",
            details = reason.take(500),
            suggestion = "Check environment variables and dependencies for this provider."
        )
    }
}
