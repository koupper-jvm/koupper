package com.koupper.providers.k8s

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class K8sProviderTest : AnnotationSpec() {

    @Test
    fun `rolloutStatus should return structured result on timeout or launch failure instead of throwing`() {
        // The provider is exercised with a zero-second timeout so the real kubectl binary
        // either times out immediately or fails to launch — either way no exception should escape.
        val provider = KubectlK8sProvider(kubectl = "kubectl", timeoutSeconds = 0)
        val result = provider.rolloutStatus("nonexistent-deployment", "default")

        // Must not throw — must return a K8sResult with a non-zero exit code
        assertTrue(result.exitCode != 0, "Expected non-zero exit on timeout or launch failure")
        assertTrue(result.command.isNotBlank(), "command field must always be populated")
        assertTrue(result.stderr.isNotBlank(), "stderr must contain a diagnostic message")
    }

    @Test
    fun `K8sResult timedOut defaults to false`() {
        val result = K8sResult(command = "kubectl get pod", exitCode = 0, stdout = "running", stderr = "")
        assertFalse(result.timedOut)
    }

    @Test
    fun `K8sResult timedOut is true when set`() {
        val result = K8sResult(
            command = "kubectl rollout status deployment/foo -n prod",
            exitCode = 124,
            stdout = "",
            stderr = "kubectl command timed out after 120s",
            timedOut = true
        )
        assertTrue(result.timedOut)
        assertEquals(124, result.exitCode)
    }
}
