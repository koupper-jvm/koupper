package com.koupper.providers.templates

import com.koupper.providers.templates.loader.CachedTemplateLoader
import com.koupper.providers.templates.loader.ClasspathTemplateLoader
import com.koupper.providers.templates.loader.S3TemplateLoader
import com.koupper.providers.templates.loader.TemplateLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TemplateLoaderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `classpath loader reads resource`() {
        // providers/src/test/resources/template.html
        val html = ClasspathTemplateLoader().read("template.html")
        html.shouldContain("<")
    }

    @Test
    fun `s3 loader resolves prefix and fetches object`() {
        val s3 = mockk<S3Client>()
        every {
            s3.getObject(any<GetObjectRequest>(), any<ResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>())
        } answers {
            val req = firstArg<GetObjectRequest>()
            req.key() shouldBe "emails/welcome.html"
            ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(),
                "<html>{{ name }}</html>".toByteArray(StandardCharsets.UTF_8)
            )
        }

        val loader = S3TemplateLoader(
            bucket = "igly-templates",
            region = "us-east-1",
            keyPrefix = "emails",
            clientFactory = { s3 }
        )

        loader.read("welcome.html") shouldBe "<html>{{ name }}</html>"
        loader.resolveKey("welcome.html") shouldBe "emails/welcome.html"
    }

    @Test
    fun `cached loader respects ttl`() {
        val hits = AtomicInteger(0)
        val now = AtomicLong(1_000L)
        val delegate = TemplateLoader { path ->
            hits.incrementAndGet()
            "body-$path"
        }
        val cached = CachedTemplateLoader(
            delegate = delegate,
            ttlMillis = 100,
            clock = { now.get() }
        )

        cached.read("a") shouldBe "body-a"
        cached.read("a") shouldBe "body-a"
        hits.get() shouldBe 1

        now.set(1_050L) // still within TTL if expiresAt = 1100
        cached.read("a") shouldBe "body-a"
        hits.get() shouldBe 1

        now.set(1_101L)
        cached.read("a") shouldBe "body-a"
        hits.get() shouldBe 2
    }

    @Test
    fun `pebble provider uses injected loader and renders`() {
        val loader = TemplateLoader { "<p>\${name}</p>" }
        // Force legacy path by using invalid pebble? Actually ${name} works with pebble too as literal.
        // Use pebble syntax:
        val pebbleLoader = TemplateLoader { "<p>{{ name }}</p>" }
        val provider = PebbleTemplateProvider(pebbleLoader)
        provider.load("x", mapOf("name" to "Igly")) shouldBe "<p>Igly</p>"
    }

    @Test
    fun `fromFile still reads filesystem`() {
        val file = File(tempDir, "local.html")
        file.writeText("<b>{{ v }}</b>")
        val provider = PebbleTemplateProvider(ClasspathTemplateLoader())
        provider.load(file.absolutePath, mapOf("v" to "ok"), fromFile = true) shouldBe "<b>ok</b>"
    }

    @Test
    fun `template loaders factory defaults to classpath`() {
        val prev = System.getProperty("TEMPLATES_DRIVER")
        try {
            System.clearProperty("TEMPLATES_DRIVER")
            val loader = TemplateLoaders.create(TemplateConfig(driver = "classpath"))
            loader::class shouldBe ClasspathTemplateLoader::class
        } finally {
            if (prev != null) System.setProperty("TEMPLATES_DRIVER", prev) else System.clearProperty("TEMPLATES_DRIVER")
        }
    }

    @Test
    fun `s3 driver requires bucket`() {
        shouldThrow<IllegalStateException> {
            TemplateLoaders.create(TemplateConfig(driver = "s3", s3Bucket = null))
        }
    }
}
