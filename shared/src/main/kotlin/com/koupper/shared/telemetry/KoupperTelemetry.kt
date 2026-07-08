package com.koupper.shared.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.exporter.logging.LoggingSpanExporter

/**
 * OpenTelemetry tracer for Koupper script execution.
 *
 * Provides automatic span creation around script runs, pipeline steps,
 * and provider calls. Exports spans to stdout via LoggingSpanExporter
 * (default); can be switched to OTLP by setting environment variables.
 *
 * Enable/disable:
 *   -Dkoupper.telemetry.enabled=true
 *   or KOUPPER_TELEMETRY_ENABLED=true
 *
 * Service name:
 *   -Dkoupper.telemetry.service=koupper-daemon
 *   or KOUPPER_TELEMETRY_SERVICE=koupper-daemon
 */
object KoupperTelemetry {

    private val enabled: Boolean by lazy {
        System.getProperty("koupper.telemetry.enabled")?.toBooleanStrictOrNull()
            ?: System.getenv("KOUPPER_TELEMETRY_ENABLED")?.toBooleanStrictOrNull()
            ?: false
    }

    private val serviceName: String by lazy {
        System.getProperty("koupper.telemetry.service")
            ?: System.getenv("KOUPPER_TELEMETRY_SERVICE")
            ?: "koupper-daemon"
    }

    private val openTelemetry: OpenTelemetry? by lazy {
        if (!enabled) return@lazy null

        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(LoggingSpanExporter.create()).build())
            .build()

        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()

        Runtime.getRuntime().addShutdownHook(Thread {
            tracerProvider.shutdown()
        })

        sdk
    }

    private val tracer: Tracer? by lazy {
        openTelemetry?.getTracer(serviceName, "7.1.1")
    }

    /**
     * Returns true if telemetry is enabled.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Creates a new span around the given [block].
     *
     * @param name span name (e.g., "script.run", "pipeline.stage")
     * @param kind span kind (default: INTERNAL)
     * @param attributes key-value pairs to attach to the span
     * @param block the code to execute inside the span
     * @return the result of [block]
     */
    fun <T> withSpan(
        name: String,
        kind: SpanKind = SpanKind.INTERNAL,
        attributes: Map<String, String> = emptyMap(),
        block: (Span) -> T
    ): T {
        val span = tracer?.spanBuilder(name)
            ?.setSpanKind(kind)
            ?.also { builder ->
                attributes.forEach { (k, v) -> builder.setAttribute(k, v) }
            }
            ?.startSpan()

        return if (span != null) {
            try {
                val result = block(span)
                span.setStatus(StatusCode.OK)
                result
            } catch (e: Throwable) {
                span.recordException(e)
                span.setStatus(StatusCode.ERROR, e.message ?: "unknown error")
                throw e
            } finally {
                span.end()
            }
        } else {
            block(Span.getInvalid())
        }
    }

    /**
     * Injects the current trace context into a map of headers/strings.
     * Useful for propagating trace context to downstream services (HTTP, jobs, etc.).
     */
    fun injectContext(carrier: MutableMap<String, String>) {
        val otel = openTelemetry ?: return
        val context = io.opentelemetry.context.Context.current()
        otel.propagators.textMapPropagator.inject(context, carrier) { map: MutableMap<String, String>?, key: String, value: String ->
            map?.set(key, value)
        }
    }

    /**
     * Extracts trace context from a map of headers/strings and makes it current.
     */
    fun extractContext(carrier: Map<String, String>): io.opentelemetry.context.Context {
        val otel = openTelemetry ?: return io.opentelemetry.context.Context.current()
        return otel.propagators.textMapPropagator.extract(
            io.opentelemetry.context.Context.current(),
            carrier,
            object : io.opentelemetry.context.propagation.TextMapGetter<Map<String, String>> {
                override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
                override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
            }
        )
    }
}
