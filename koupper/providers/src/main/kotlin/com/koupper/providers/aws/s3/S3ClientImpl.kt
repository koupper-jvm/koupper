package com.koupper.providers.aws.s3

import com.koupper.os.env
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.time.Duration

class S3ClientImpl private constructor(
    private val explicitCredsProvider: AwsCredentialsProvider?
) : S3Client {

    constructor() : this(null)

    fun withCredentials(
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String? = null
    ): S3ClientImpl {
        val provider =
            if (!sessionToken.isNullOrBlank())
                StaticCredentialsProvider.create(AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken))
            else
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))

        return S3ClientImpl(provider)
    }

    private fun envCredsProvider(): AwsCredentialsProvider {
        val ak = env("AWS_ACCESS_KEY_ID", required = false, allowEmpty = true, default = "")
        val sk = env("AWS_SECRET_ACCESS_KEY", required = false, allowEmpty = true, default = "")
        val st = env("AWS_SESSION_TOKEN", required = false, allowEmpty = true, default = "")

        return when {
            ak.isNotBlank() && sk.isNotBlank() && st.isNotBlank() ->
                StaticCredentialsProvider.create(AwsSessionCredentials.create(ak, sk, st))
            ak.isNotBlank() && sk.isNotBlank() ->
                StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk))
            else ->
                EnvironmentVariableCredentialsProvider.create()
        }
    }

    private val bucket = env("QUIZZTEA_S3_BUCKET")
    private val region = env("AWS_REGION")
    private val customEndpoint = env("S3_URL", required = false, allowEmpty = true, default = "")

    private val presigner: S3Presigner by lazy {
        val credsForAws =
            explicitCredsProvider ?: envCredsProvider()

        S3Presigner.builder()
            .region(Region.of(region))
            .apply {
                if (customEndpoint.isNotEmpty()) {
                    endpointOverride(URI(customEndpoint))
                    credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")
                        )
                    )
                } else {
                    credentialsProvider(credsForAws)
                }
            }
            .build()
    }

    override fun getPresignedUploadUrl(
        fileName: String,
        fileType: String
    ): PresignedResult {

        val putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(fileName)
            .contentType(fileType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(10))
            .putObjectRequest(putRequest)
            .build()

        val presigned = presigner.presignPutObject(presignRequest)

        val uploadUrl = presigned.url().toString()
        val publicUrl =
            if (customEndpoint.isNotEmpty()) "$customEndpoint/$bucket/$fileName"
            else "https://$bucket.s3.amazonaws.com/$fileName"

        return PresignedResult(
            statusCode = 200,
            uploadUrl = uploadUrl,
            publicUrl = publicUrl
        )
    }
}

fun main() {
    val a = S3ClientImpl()
    a.getPresignedUploadUrl("a", "b")
}