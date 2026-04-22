package com.koupper.providers.aws.s3

data class PresignedResult(
    val statusCode: Int,
    val uploadUrl: String,
    val publicUrl: String
)

interface S3Client {
    fun getPresignedUploadUrl(
        fileName: String,
        fileType: String
    ): PresignedResult
}