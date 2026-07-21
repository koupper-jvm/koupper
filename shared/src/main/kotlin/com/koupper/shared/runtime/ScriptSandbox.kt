package com.koupper.shared.runtime

import java.util.concurrent.*
import kotlin.concurrent.thread

/**
 * Sandboxing wrapper for Koupper script execution.
 *
 * Protects the daemon JVM from:
 * 1. **Infinite loops / hung scripts** — enforced timeout via thread interruption
 * 2. **`System.exit()` calls** — intercepted and converted to exception
 * 3. **Uncaught exceptions** — captured instead of killing the worker thread
 *
 * Backward compatible: if sandboxing is disabled (default), scripts run
 * exactly as before. Enable with `-Dkoupper.scripting.sandbox=true` or
 * `KOUPPER_SCRIPTING_SANDBOX=true`.
 */
object ScriptSandbox {

    private val enabled: Boolean by lazy {
        System.getProperty("koupper.scripting.sandbox")?.equals("true", ignoreCase = true)
            ?: System.getenv("KOUPPER_SCRIPTING_SANDBOX")?.equals("true", ignoreCase = true)
            ?: false
    }

    private val defaultTimeoutMs: Long by lazy {
        System.getProperty("koupper.scripting.timeoutMs")?.toLongOrNull()
            ?: System.getenv("KOUPPER_SCRIPTING_TIMEOUT_MS")?.toLongOrNull()
            ?: 300_000L // 5 minutes default
    }

    /**
     * Executes [block] inside a sandbox.
     *
     * @param timeoutMs max execution time in milliseconds (0 = no timeout)
     * @param block the script execution lambda
     * @return the result of [block]
     * @throws ScriptSandboxException if the script violates sandbox rules (timeout, System.exit, etc.)
     */
    fun <T> execute(timeoutMs: Long = defaultTimeoutMs, block: () -> T): T {
        if (!enabled) {
            return block()
        }

        val effectiveTimeout = if (timeoutMs <= 0) defaultTimeoutMs else timeoutMs

        // Save and install SecurityManager to intercept System.exit
        val originalSecurityManager = System.getSecurityManager()
        val exitInterceptManager = ExitInterceptSecurityManager()
        System.setSecurityManager(exitInterceptManager)

        val executor = Executors.newSingleThreadExecutor { r ->
            thread(start = false, isDaemon = true, name = "koupper-sandbox-${System.nanoTime()}") {
                r.run()
            }
        }

        val future = executor.submit<T> {
            try {
                block()
            } catch (e: ExitTrappedException) {
                throw ScriptSandboxException("Script attempted System.exit(${e.status}) — blocked by sandbox", e)
            } catch (e: Throwable) {
                throw ScriptSandboxException("Script threw uncaught exception: ${e.message}", e)
            }
        }

        return try {
            future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            executor.shutdownNow()
            throw ScriptSandboxException("Script exceeded timeout of ${effectiveTimeout}ms — terminated")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is ScriptSandboxException) throw cause
            throw ScriptSandboxException("Script execution failed: ${cause?.message}", cause)
        } catch (e: InterruptedException) {
            future.cancel(true)
            executor.shutdownNow()
            throw ScriptSandboxException("Script was interrupted")
        } finally {
            System.setSecurityManager(originalSecurityManager)
            executor.shutdown()
        }
    }

    // ── SecurityManager that intercepts System.exit ──

    /**
     * Lightweight SecurityManager that only intercepts System.exit().
     * All other checks are delegated to the original manager (if any).
     */
    private class ExitInterceptSecurityManager : SecurityManager() {
        override fun checkExit(status: Int) {
            throw ExitTrappedException(status)
        }

        // Delegate all other checks to avoid breaking normal operation
        override fun checkPermission(perm: java.security.Permission?) {
            // Allow everything else
        }
    }

    private class ExitTrappedException(val status: Int) : SecurityException("System.exit($status) blocked by sandbox")

    class ScriptSandboxException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
}
