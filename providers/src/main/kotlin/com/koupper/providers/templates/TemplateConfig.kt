package com.koupper.providers.templates

import com.koupper.os.env
import com.koupper.providers.templates.loader.CachedTemplateLoader
import com.koupper.providers.templates.loader.ClasspathTemplateLoader
import com.koupper.providers.templates.loader.S3TemplateLoader
import com.koupper.providers.templates.loader.TemplateLoader

/**
 * Externalized template asset configuration (driver pattern).
 *
 * Env (also readable as system properties with the same names):
 * - `TEMPLATES_DRIVER` = `classpath` (default) | `s3`
 * - `TEMPLATES_S3_BUCKET` (required when driver=s3)
 * - `TEMPLATES_S3_REGION` (default `us-east-1`)
 * - `TEMPLATES_S3_PREFIX` (optional key prefix)
 * - `TEMPLATES_CACHE_ENABLED` (default `true` for s3, `false` for classpath)
 * - `TEMPLATES_CACHE_TTL_SECONDS` (default `300`)
 */
data class TemplateConfig(
    val driver: String = "classpath",
    val s3Bucket: String? = null,
    val s3Region: String = "us-east-1",
    val s3Prefix: String = "",
    val cacheEnabled: Boolean = false,
    val cacheTtlSeconds: Long = 300
) {
    companion object {
        fun fromEnvironment(): TemplateConfig {
            val driver = propOrEnv("TEMPLATES_DRIVER", "classpath").lowercase()
            val cacheDefault = if (driver == "s3") "true" else "false"
            return TemplateConfig(
                driver = driver,
                s3Bucket = propOrEnv("TEMPLATES_S3_BUCKET", "").ifBlank { null },
                s3Region = propOrEnv("TEMPLATES_S3_REGION", "us-east-1"),
                s3Prefix = propOrEnv("TEMPLATES_S3_PREFIX", ""),
                cacheEnabled = propOrEnv("TEMPLATES_CACHE_ENABLED", cacheDefault)
                    .equals("true", ignoreCase = true),
                cacheTtlSeconds = propOrEnv("TEMPLATES_CACHE_TTL_SECONDS", "300").toLongOrNull() ?: 300L
            )
        }

        private fun propOrEnv(name: String, default: String): String {
            System.getProperty(name)?.takeIf { it.isNotBlank() }?.let { return it }
            return env(name, required = false, default = default)
        }
    }
}

object TemplateLoaders {
    fun create(config: TemplateConfig = TemplateConfig.fromEnvironment()): TemplateLoader {
        val base: TemplateLoader = when (config.driver) {
            "classpath", "file" -> ClasspathTemplateLoader()
            "s3" -> {
                val bucket = config.s3Bucket
                    ?: error("TEMPLATES_DRIVER=s3 requires TEMPLATES_S3_BUCKET")
                S3TemplateLoader(
                    bucket = bucket,
                    region = config.s3Region,
                    keyPrefix = config.s3Prefix
                )
            }
            else -> error("Unknown TEMPLATES_DRIVER='${config.driver}'. Supported: classpath, s3")
        }

        return if (config.cacheEnabled) {
            CachedTemplateLoader(
                delegate = base,
                ttlMillis = config.cacheTtlSeconds.coerceAtLeast(0L) * 1000L
            )
        } else {
            base
        }
    }
}
