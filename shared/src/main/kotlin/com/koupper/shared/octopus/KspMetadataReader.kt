package com.koupper.shared.octopus

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/**
 * Runtime reader for KSP-generated metadata.
 *
 * Replaces regex-based extraction by reading the JSON file generated
 * at compile time by KoupperSymbolProcessor.
 */
object KspMetadataReader {
    
    private val mapper = jacksonObjectMapper()
    
    data class ExportEntry(
        val packageName: String,
        val propertyName: String,
        val type: String,
        val annotations: Map<String, Map<String, String>>
    )
    
    data class ScheduledEntry(
        val packageName: String,
        val propertyName: String,
        val cron: String?,
        val rate: Long?,
        val delay: Long?,
        val at: String?,
        val configId: String?,
        val chain: String?
    )
    
    data class PipelineEntry(
        val packageName: String,
        val propertyName: String,
        val cron: String?,
        val chain: String,
        val id: String
    )
    
    data class KoupperMetadata(
        val exports: List<ExportEntry>,
        val scheduled: List<ScheduledEntry>,
        val pipelines: List<PipelineEntry>
    )
    
    /**
     * Reads metadata from the KSP-generated JSON file.
     * 
     * @param metadataFile The JSON file (default: looks in classpath for koupper-exports.json)
     * @return Parsed metadata or null if file not found
     */
    fun read(metadataFile: File? = null): KoupperMetadata? {
        val file = metadataFile ?: findMetadataFile()
        return file?.let { mapper.readValue<KoupperMetadata>(it) }
    }
    
    /**
     * Finds the metadata file in the classpath.
     */
    private fun findMetadataFile(): File? {
        val classLoader = Thread.currentThread().contextClassLoader
        val resource = classLoader.getResource("com/koupper/generated/koupper-exports.json")
        return resource?.let { File(it.toURI()) }
    }
    
    /**
     * Checks if KSP metadata is available (i.e., the project has been compiled with KSP).
     */
    fun isAvailable(): Boolean = findMetadataFile() != null
}
