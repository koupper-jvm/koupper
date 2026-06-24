package com.koupper.providers

/**
 * Tier classification for ServiceProviders.
 *
 * | Tier | Criteria | CI Gate |
 * |------|----------|---------|
 * | CORE | >80% test coverage, fully documented, exception-safe, schema-typed I/O | Block merge if tests fail |
 * | COMMUNITY | Basic happy-path tests, documented | Warn on no-test merge |
 * | EXPERIMENTAL | No test requirement, marked @Experimental | Excluded from fatJar by default |
 */
enum class ProviderTier {
    CORE,
    COMMUNITY,
    EXPERIMENTAL
}
