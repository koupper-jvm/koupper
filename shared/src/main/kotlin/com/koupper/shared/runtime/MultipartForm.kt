package com.koupper.shared.runtime

import java.io.File

/**
 * Represents a multipart/form-data request body natively in Koupper.
 */
class MultipartForm(
    val fields: Map<String, String> = emptyMap(),
    val files: Map<String, UploadedFile> = emptyMap()
) {
    fun getFile(name: String): UploadedFile? = files[name]
    fun getField(name: String): String? = fields[name]
}

data class UploadedFile(
    val filename: String,
    val contentType: String,
    val content: ByteArray
) {
    fun saveTo(path: String) {
        File(path).writeBytes(content)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UploadedFile
        if (filename != other.filename) return false
        if (contentType != other.contentType) return false
        return content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}
