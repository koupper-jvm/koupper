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
    private val scheduled = mutableListOf<ScheduledMetadata>()
    private val pipelines = mutableListOf<PipelineMetadata>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val unableToProcess = mutableListOf<KSAnnotated>()
        
        // Process @Export
        val exportSymbols = resolver.getSymbolsWithAnnotation("com.koupper.shared.annotations.Export")
        unableToProcess.addAll(exportSymbols.filter { !it.validate() })
        exportSymbols.filter { it is KSPropertyDeclaration && it.validate() }
            .forEach { processExport(it as KSPropertyDeclaration) }
        
        // Process @Scheduled
        val scheduledSymbols = resolver.getSymbolsWithAnnotation("com.koupper.shared.annotations.Scheduled")
        unableToProcess.addAll(scheduledSymbols.filter { !it.validate() })
        scheduledSymbols.filter { it is KSPropertyDeclaration && it.validate() }
            .forEach { processScheduled(it as KSPropertyDeclaration) }
        
        // Process @Pipeline
        val pipelineSymbols = resolver.getSymbolsWithAnnotation("com.koupper.shared.annotations.Pipeline")
        unableToProcess.addAll(pipelineSymbols.filter { !it.validate() })
        pipelineSymbols.filter { it is KSPropertyDeclaration && it.validate() }
            .forEach { processPipeline(it as KSPropertyDeclaration) }
        
        // Generate metadata file if we found anything
        if (exports.isNotEmpty() || scheduled.isNotEmpty() || pipelines.isNotEmpty()) {
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
            
            // Exports
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
            appendLine("  ],")
            
            // Scheduled
            appendLine("  \"scheduled\": [")
            scheduled.forEachIndexed { index, s ->
                appendLine("    {")
                appendLine("      \"packageName\": \"${s.packageName}\",")
                appendLine("      \"propertyName\": \"${s.propertyName}\",")
                s.cron?.let { appendLine("      \"cron\": \"$it\",") }
                s.rate?.let { appendLine("      \"rate\": $it,") }
                s.delay?.let { appendLine("      \"delay\": $it,") }
                s.at?.let { appendLine("      \"at\": \"$it\",") }
                s.configId?.let { appendLine("      \"configId\": \"$it\",") }
                s.chain?.let { appendLine("      \"chain\": \"$it\",") }
                appendLine("      \"_dummy\": true")
                val comma = if (index < scheduled.size - 1) "," else ""
                appendLine("    }$comma")
            }
            appendLine("  ],")
            
            // Pipelines
            appendLine("  \"pipelines\": [")
            pipelines.forEachIndexed { index, p ->
                appendLine("    {")
                appendLine("      \"packageName\": \"${p.packageName}\",")
                appendLine("      \"propertyName\": \"${p.propertyName}\",")
                p.cron?.let { appendLine("      \"cron\": \"$it\",") }
                appendLine("      \"chain\": \"${p.chain}\",")
                appendLine("      \"id\": \"${p.id}\"")
                val comma = if (index < pipelines.size - 1) "," else ""
                appendLine("    }$comma")
            }
            appendLine("  ]")
            
            appendLine("}")
        }
        
        file.use { it.write(json.toByteArray()) }
    }
    
    private fun processScheduled(property: KSPropertyDeclaration) {
        val packageName = property.packageName.asString()
        val propertyName = property.simpleName.asString()
        
        val scheduledAnn = property.annotations.find { it.shortName.asString() == "Scheduled" }
        val args = scheduledAnn?.arguments?.associate { 
            (it.name?.asString() ?: "") to (it.value?.toString() ?: "")
        } ?: emptyMap()
        
        logger.info("KSP: Found @Scheduled property '$propertyName' in package '$packageName' with args: $args")
        
        scheduled.add(ScheduledMetadata(
            packageName = packageName,
            propertyName = propertyName,
            cron = args["cron"],
            rate = args["rate"]?.toLongOrNull(),
            delay = args["delay"]?.toLongOrNull(),
            at = args["at"],
            configId = args["configId"],
            chain = args["chain"]
        ))
    }
    
    private fun processPipeline(property: KSPropertyDeclaration) {
        val packageName = property.packageName.asString()
        val propertyName = property.simpleName.asString()
        
        val pipelineAnn = property.annotations.find { it.shortName.asString() == "Pipeline" }
        val args = pipelineAnn?.arguments?.associate { 
            (it.name?.asString() ?: "") to (it.value?.toString() ?: "")
        } ?: emptyMap()
        
        logger.info("KSP: Found @Pipeline property '$propertyName' in package '$packageName' with args: $args")
        
        pipelines.add(PipelineMetadata(
            packageName = packageName,
            propertyName = propertyName,
            cron = args["cron"],
            chain = args["chain"] ?: "",
            id = args["id"] ?: ""
        ))
    }
    
    data class ExportMetadata(
        val packageName: String,
        val propertyName: String,
        val type: String,
        val annotations: Map<String, Map<String, String>>
    )
    
    data class ScheduledMetadata(
        val packageName: String,
        val propertyName: String,
        val cron: String?,
        val rate: Long?,
        val delay: Long?,
        val at: String?,
        val configId: String?,
        val chain: String?
    )
    
    data class PipelineMetadata(
        val packageName: String,
        val propertyName: String,
        val cron: String?,
        val chain: String,
        val id: String
    )
}

class KoupperSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return KoupperSymbolProcessor(environment.codeGenerator, environment.logger)
    }
}
