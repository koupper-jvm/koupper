package com.koupper.providers.aws.s3

data class S3PresignedRequest(
    val quizId: String,
    val fileName: String,
    val fileType: String,
    val objectKey: String? = null
)