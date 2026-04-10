package com.koupper.providers.iac

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IaCProviderTest : AnnotationSpec() {
    @Test
    fun `drift spec required only should report missing without extras`() {
        val provider = TerraformIaCProvider(commandRunner = { _, _, _ -> RunnerResult(0, "", "", false) })
        val spec = """
            {
              "version": "1",
              "mode": "required_only",
              "checks": {
                "dynamo": { "tables": [ { "name": "users", "gsis": ["email-index"] } ] }
              }
            }
        """.trimIndent()
        val observed = """
            {
              "checks": {
                "dynamo": { "tables": [ { "name": "users", "gsis": [] } ] }
              }
            }
        """.trimIndent()

        val result = provider.evaluateDriftSpec(spec, observed)

        assertEquals("required_only", result.mode)
        assertContains(result.missing, "dynamo.gsi:users:email-index")
        assertTrue(result.extras.isEmpty())
    }

    @Test
    fun `drift spec exact match should report extras`() {
        val provider = TerraformIaCProvider(commandRunner = { _, _, _ -> RunnerResult(0, "", "", false) })
        val spec = """
            {
              "version": "1",
              "mode": "exact_match",
              "checks": {
                "api": { "routes": [ { "path": "/health", "method": "GET", "stage": "prod" } ] }
              }
            }
        """.trimIndent()
        val observed = """
            {
              "checks": {
                "api": {
                  "routes": [
                    { "path": "/health", "method": "GET", "stage": "prod" },
                    { "path": "/admin", "method": "GET", "stage": "prod" }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = provider.evaluateDriftSpec(spec, observed)

        assertTrue(result.missing.isEmpty())
        assertContains(result.extras, "api.route:prod:GET:/admin")
    }

    @Test
    fun `drift stage should preserve terraform detailed exit code`() {
        val provider = TerraformIaCProvider(commandRunner = { args, _, _ ->
            if (args.contains("-detailed-exitcode")) RunnerResult(2, "plan", "", false)
            else RunnerResult(0, "init", "", false)
        })

        val result = provider.drift(InfraExecutionOptions(dir = "."))

        assertEquals(2, result.exitCode)
        assertTrue(result.warnings.any { it.contains("Drift detected") })
    }
}
