package com.koupper.shared

import java.io.File
import java.io.InputStream

fun File.copyInputStreamToFile(inputStream: InputStream) {
    this.outputStream().use { fileOut ->
        inputStream.copyTo(fileOut)
    fun File.getProperty(propertyName: String) : String {
        var result = "undefined"

        if (this.exists()) {
            try {
                this.readLines().forEach { line ->
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                        val parts = trimmedLine.split("=", limit = 2)
                        if (parts.size == 2 && parts[0].trim() == propertyName) {
                            result = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                        }
                    }
                }
            } catch (e: Exception) {
                // Log if needed, but maintain the contract
            }
        }

        return result
    }

        } catch (e: Exception) {
            // Silently ignore or log if necessary, but keep the contract
        }
    }

    return result
}

fun ArrayList<Int>.toByteArray() : ByteArray {
    return this.foldIndexed(ByteArray(this.size)) { i, a, v -> a.apply { set(i, v.toByte()) } }
}

// Canonicaliza tipos simples a su forma Kotlin (o null si no es simple)
private val SIMPLE_CANON = mapOf(
    "string" to "kotlin.String", "kotlin.string" to "kotlin.String", "java.lang.string" to "kotlin.String",
    "int" to "kotlin.Int", "kotlin.int" to "kotlin.Int", "java.lang.integer" to "kotlin.Int",
    "long" to "kotlin.Long", "kotlin.long" to "kotlin.Long", "java.lang.long" to "kotlin.Long",
    "double" to "kotlin.Double", "kotlin.double" to "kotlin.Double", "java.lang.double" to "kotlin.Double",
    "boolean" to "kotlin.Boolean", "kotlin.boolean" to "kotlin.Boolean", "java.lang.boolean" to "kotlin.Boolean",
    "float" to "kotlin.Float", "kotlin.float" to "kotlin.Float", "java.lang.float" to "kotlin.Float",
    "short" to "kotlin.Short", "kotlin.short" to "kotlin.Short", "java.lang.short" to "kotlin.Short",
    "byte" to "kotlin.Byte", "kotlin.byte" to "kotlin.Byte", "java.lang.byte" to "kotlin.Byte",
    "char" to "kotlin.Char", "kotlin.char" to "kotlin.Char", "java.lang.character" to "kotlin.Char"
)

private fun String.stripNullability() = trim().removeSuffix("?")
private fun String.keyForLookup() = stripNullability().lowercase()

fun String.canonicalSimpleTypeOrNull(): String? = SIMPLE_CANON[keyForLookup()]
fun String.isSimpleType(): Boolean = canonicalSimpleTypeOrNull() != null
fun String.normalizeType(): String = trim().removeSuffix("?").substringAfterLast('.')
