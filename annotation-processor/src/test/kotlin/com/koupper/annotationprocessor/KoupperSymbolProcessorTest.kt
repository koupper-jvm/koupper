package com.koupper.annotationprocessor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*

/**
 * Unit tests for KoupperSymbolProcessor.
 *
 * Verifies processor initialization and basic behavior
 * without requiring full KSP compilation.
 */
class KoupperSymbolProcessorTest : ShouldSpec({

    should("create processor from provider") {
        val mockCodeGenerator = mockk<CodeGenerator>(relaxed = true)
        val mockLogger = mockk<KSPLogger>(relaxed = true)
        
        val environment = mockk<SymbolProcessorEnvironment> {
            every { codeGenerator } returns mockCodeGenerator
            every { logger } returns mockLogger
        }
        
        val provider = KoupperSymbolProcessorProvider()
        val processor = provider.create(environment)
        
        processor shouldNotBe null
    }

    should("process empty resolver without errors") {
        val mockCodeGenerator = mockk<CodeGenerator>(relaxed = true)
        val mockLogger = mockk<KSPLogger>(relaxed = true)
        val processor = KoupperSymbolProcessor(mockCodeGenerator, mockLogger)
        
        val mockResolver = mockk<Resolver> {
            every { getSymbolsWithAnnotation("com.koupper.shared.annotations.Export") } returns emptySequence()
        }
        
        val result = processor.process(mockResolver)
        
        result.shouldBe(emptyList())
    }
})
