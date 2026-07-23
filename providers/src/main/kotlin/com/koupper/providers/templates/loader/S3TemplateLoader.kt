package com.koupper.providers.templates.loader

import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.nio.charset.StandardCharsets

/**
 * Loads templates via S3 GetObject.
 *
 * @param bucket S3 bucket name
 * @param region AWS region id
 * @param keyPrefix optional prefix prepended to [path] (e.g. `emails/`)
 * @param clientFactory lazy client factory (overridable in tests)
 */
class S3TemplateLoader(
    private val bucket: String,
    private val region: String,
    private val keyPrefix: String = "",
    private val clientFactory: () -> S3Client = {
        S3Client.builder()
            .region(Region.of(region))
            .build()
    }
) : TemplateLoader, AutoCloseable {

    private val client: S3Client by lazy(clientFactory)

    override fun read(path: String): String {
        val key = resolveKey(path)
        return try {
            val bytes = client.getObject(
                GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
                ResponseTransformer.toBytes()
            )
            bytes.asString(StandardCharsets.UTF_8)
        } catch (e: NoSuchKeyException) {
            throw IllegalArgumentException("Template not found in s3://$bucket/$key", e)
        }
    }

    internal fun resolveKey(path: String): String {
        val normalizedPath = path.trim().trimStart('/')
        val prefix = keyPrefix.trim().trim('/')
        return if (prefix.isBlank()) normalizedPath else "$prefix/$normalizedPath"
    }

    override fun close() {
        runCatching { client.close() }
    }
}
