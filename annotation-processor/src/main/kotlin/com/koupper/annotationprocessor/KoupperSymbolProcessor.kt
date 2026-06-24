package com.koupper.annotationprocessor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate

/**
 * KSP Symbol Processor for Koupper annotations.
 *
 * Replaces regex-based annotation extraction with compiler-aware processing.
 * Processes @Export, @Scheduled, @Pipeline annotations at compile time.
 */
class KoupperSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private val exports = mutableListOf<ExportMetadata>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("com.koupper.shared.annotations.Export")
        
        val unableToProcess = symbols.filter { !it.validate() }.toList()
        
        symbols.filter { it is KSPropertyDeclaration && it.validate() }
            .forEach { symbol ->
                processExport(symbol as KSPropertyDeclaration)
            }
        
        // Generate metadata file if we found any exports
        if (exports.isNotEmpty()) {
            generateMetadataFile()
        }
        
        return unableToProcess
    }

    private fun processExport(property: KSPropertyDeclaration) {
        val packageName = property.packageName.asString()
        val propertyName = property.simpleName.asString()
        
        // Extract type information from the property type
        val typeReference = property.type
        val typeString = typeReference?.resolve()?.declaration?.qualifiedName?.asString() ?: "Unknown"
        
        // Get all annotations on this property
        val annotations: Map<String, Map<String, String>> = property.annotations.map { ann ->
            val name = ann.shortName.asString()
            val args: Map<String, String> = ann.arguments.associate { arg ->
                (arg.name?.asString() ?: "") to (arg.value?.toString() ?: "")
            }
            name to args
        }.toMap()
        
        logger.info("KSP: Found @Export property '$propertyName' of type '$typeString' in package '$packageName'")
        
        exports.add(ExportMetadata(
            packageName = packageName,
            propertyName = propertyName,
            type = typeString,
            annotations = annotations
        ))
    }
    
    private fun generateMetadataFile() {
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false),
            packageName = "com.koupper.generated",
            fileName = "koupper-exports",
            extensionName = "json"
        )
        
        val json = buildString {
            appendLine("{")
            appendLine("  \"exports\": [")
            exports.forEachIndexed { index, export ->
                appendLine("    {")
                appendLine("      \"packageName\": \"${export.packageName}\",")
                appendLine("      \"propertyName\": \"${export.propertyName}\",")
                appendLine("      \"type\": \"${export.type}\",")
                appendLine("      \"annotations\": {")
                export.annotations.entries.forEachIndexed { annIndex, (annName, annArgs) ->
                    appendLine("        \"$annName\": {")
                    annArgs.entries.forEachIndexed { argIndex, (argName, argValue) ->
                        val comma = if (argIndex < annArgs.size - 1) "," else ""
                        appendLine("          \"$argName\": \"$argValue\"$comma")
                    }
                    val comma = if (annIndex < export.annotations.size - 1) "," else ""
                    appendLine("        }$comma")
                }
                appendLine("      }")
                val comma = if (index < exports.size - 1) "," else ""
                appendLine("    }$comma")
            }
            appendLine("  ]")
            appendLine("}")
        }
        
        file.use { it.write(json.toByteArray()) }
    }
    
    data class ExportMetadata(
        val packageName: String,
        val propertyName: String,
        val type: String,
        val annotations: Map<String, Map<String, String>>
    )
}

class KoupperSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return KoupperSymbolProcessor(environment.codeGenerator, environment.logger)
    }
}
