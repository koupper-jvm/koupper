# Migration: K8sProvider timeout returns structured result

**Released in:** v6.4.0  
**Affects:** `K8sProvider` / `KubectlK8sProvider`

## What changed

`KubectlK8sProvider` previously threw `IllegalStateException` when a `kubectl` command exceeded `timeoutSeconds`. It now returns a structured `K8sResult` with `exitCode = 124`, `timedOut = true`, and a descriptive `stderr` message — consistent with how `TerraformIaCProvider` handles timeouts.

A `timedOut: Boolean = false` field was added to `K8sResult` (additive, default preserves backward compatibility for existing deserialization).

A launch failure (kubectl binary not on PATH) previously propagated as an unhandled exception and now returns `exitCode = 127` with the error message in `stderr`.

## Before

```kotlin
try {
    val result = k8sProvider.rolloutStatus("my-deployment", "prod")
    // use result
} catch (e: IllegalStateException) {
    // handle timeout
}
```

## After

```kotlin
val result = k8sProvider.rolloutStatus("my-deployment", "prod")
if (result.timedOut) {
    // handle timeout
}
if (result.exitCode != 0) {
    // handle failure (including timeout: exitCode == 124, launch failure: exitCode == 127)
}
```

## Why

Throwing from provider operations forces every call site to use try-catch for operational failures. The framework convention (established in `IaCProvider`, `AwsDeployProvider`) is to return structured results. This change aligns K8s with that contract.
