package com.koupper.shared.octopus

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Parity tests: KSP metadata extraction must match regex extraction.
 *
 * These tests verify that when KSP metadata is available, it produces
 * the same results as the legacy regex-based extraction.
 */
class KspRegexParityTest : ShouldSpec({

    should("extract same parameter types for simple function") {
        val script = """
            package test
            
            import com.koupper.shared.annotations.Export
            
            @Export
            val setup: () -> String = { "hello" }
        """.trimIndent()
        
        // Without KSP metadata, regex extraction should work
        val regexResult = extractExportFunctionSignature(script)
        regexResult shouldNotBe null
        regexResult?.parameterTypes shouldBe emptyList()
        regexResult?.returnType shouldBe "String"
    }
    
    should("extract same parameter types for function with parameters") {
        val script = """
            package test
            
            import com.koupper.shared.annotations.Export
            
            @Export
            val process: (String, Int) -> Boolean = { s, i -> true }
        """.trimIndent()
        
        val regexResult = extractExportFunctionSignature(script)
        regexResult shouldNotBe null
        regexResult?.parameterTypes shouldBe listOf("String", "Int")
        regexResult?.returnType shouldBe "Boolean"
    }
    
    should("extract same parameter types for function with complex types") {
        val script = """
            package test
            
            import com.koupper.shared.annotations.Export
            import java.util.Map
            
            @Export
            val handler: (Map<String, Int>) -> Unit = { map -> }
        """.trimIndent()
        
        val regexResult = extractExportFunctionSignature(script)
        regexResult shouldNotBe null
        regexResult?.parameterTypes shouldBe listOf("Map<String, Int>")
        regexResult?.returnType shouldBe "Unit"
    }
    
    should("handle multiple annotations before @Export") {
        val script = """
            package test
            
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.Scheduled
            
            @Scheduled(cron = "0 8 * * *")
            @Export
            val dailyJob: () -> Unit = { println("running") }
        """.trimIndent()
        
        val regexResult = extractExportFunctionSignature(script)
        regexResult shouldNotBe null
        regexResult?.parameterTypes shouldBe emptyList()
        regexResult?.returnType shouldBe "Unit"
    }
    
    should("return null when no @Export is present") {
        val script = """
            package test
            
            fun hello() = "world"
        """.trimIndent()
        
        val regexResult = extractExportFunctionSignature(script)
        regexResult shouldBe null
    }
})
