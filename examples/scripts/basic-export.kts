import com.koupper.shared.annotations.Export

/**
 * Basic Koupper script using KSP-processed @Export.
 *
 * This script demonstrates the single-entrypoint contract.
 * KSP extracts the @Export annotation at compile time and generates
 * metadata in koupper-exports.json — no regex parsing at runtime.
 */
@Export
val setup: () -> String = {
    "Hello from KSP-processed @Export!"
}
