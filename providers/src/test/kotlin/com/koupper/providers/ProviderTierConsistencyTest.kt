package com.koupper.providers

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tier system consistency test.
 *
 * Validates that:
 * - CORE providers have corresponding test classes
 * - EXPERIMENTAL providers are correctly marked
 * - All providers have a tier (defaults to COMMUNITY)
 */
class ProviderTierConsistencyTest : ShouldSpec({

    val allProviders = ServiceProvider.discoverProviderClasses()
        .mapNotNull { runCatching { it.constructors.first().call() as ServiceProvider }.getOrNull() }

    should("all discovered providers have a tier assigned") {
        allProviders.forEach { provider ->
            provider.tier() shouldNotBe null
        }
    }

    should("CORE providers have corresponding test classes") {
        val coreProviders = allProviders.filter { it.tier() == ProviderTier.CORE }
        val testDir = java.io.File("src/test/kotlin/com/koupper/providers")

        coreProviders.forEach { provider ->
            val simpleName = provider::class.simpleName!!
            val baseName = simpleName.replace("ServiceProvider", "")
            val testFile = testDir.walkTopDown().find { 
                it.name.contains(baseName, ignoreCase = true) && it.name.endsWith("Test.kt") 
            }
            testFile shouldNotBe null
        }
    }

    should("EXPERIMENTAL providers are correctly marked") {
        val experimental = allProviders.filter { it.tier() == ProviderTier.EXPERIMENTAL }
        experimental.size shouldBe 3
        experimental.map { it::class.simpleName }.shouldContain("AILlmOpsServiceProvider")
        experimental.map { it::class.simpleName }.shouldContain("VisionServiceProvider")
        experimental.map { it::class.simpleName }.shouldContain("SpeechToTextServiceProvider")
    }

    should("COMMUNITY providers are the default when not explicitly overridden") {
        val communityProviders = allProviders.filter { it.tier() == ProviderTier.COMMUNITY }
        // Most providers should be COMMUNITY by default
        communityProviders.size shouldBe (allProviders.size - coreProviders().size - 3) // 3 experimental
    }

    should("tier counts are reasonable") {
        val tierCounts = allProviders.groupingBy { it.tier() }.eachCount()
        tierCounts[ProviderTier.CORE] shouldBe 5 // DB, File, Http, SSH, Logger
        tierCounts[ProviderTier.EXPERIMENTAL] shouldBe 3
    }
})

private fun coreProviders() = listOf(
    "DBServiceProvider",
    "FileServiceProvider",
    "HttpServiceProvider",
    "SSHServiceProvider",
    "LoggerServiceProvider"
)
