# Contract Versioning Policy

This document defines how Koupper manages stability guarantees for CLI commands, provider contracts, and script execution contracts.

## Scope

Three contract surfaces are governed by this policy:

| Surface | Examples | Owner |
|---------|----------|-------|
| CLI command contracts | `koupper run`, `koupper infra <op>`, `koupper reconcile run` | CLI module |
| Provider contracts | `IaCProvider`, `SecretsClient`, `AwsDeployProvider` | providers module |
| Script execution contract | `@Export` entrypoint, pipeline API, JSON input/output shape | octopus module |

## Change classification

### Additive change (safe — no migration required)

- Adding a new optional parameter with a default value to an existing interface method.
- Adding a new method to a provider interface **when the only implementation is internal to the framework**.
- Adding new fields to a result data class (`InfraExecutionResult`, `AwsLambdaDeployResult`, etc.).
- Adding a new CLI subcommand that does not modify existing subcommand behavior.
- Adding a new provider to the catalog without removing or renaming existing ones.

### Behavior change (requires migration note)

- Changing the default value of an existing parameter.
- Changing the semantics of a result field (e.g., switching from throwing to returning a structured error).
- Changing a CLI flag name or its accepted values.
- Changing the environment variable name or default for a provider.

**Rule:** Every behavior change ships with a migration note in the same PR. Migration notes live in `docs/migrations/` using the format `YYYY-MM-<slug>.md`.

### Breaking change (requires major version bump + migration path)

- Removing a method from a provider interface.
- Removing or renaming a CLI command or subcommand.
- Changing a method signature in a way that breaks existing call sites.
- Changing the `@Export` entrypoint contract in a non-additive way.

**Rule:** Breaking changes require a major version bump in `koupper/build.gradle`. The old API must be kept as `@Deprecated` with a removal timeline of at minimum one minor release cycle before deletion.

## Deprecation lifecycle

```
Deprecated (current release) → Deprecated with @Deprecated annotation + migration note
  → Kept for at minimum one minor version cycle
  → Removed with a corresponding CHANGELOG entry
```

**Deprecation annotation format:**

```kotlin
@Deprecated(
    message = "Use newMethod(options) instead. Will be removed in the next minor version.",
    replaceWith = ReplaceWith("newMethod(options)")
)
fun oldMethod(param: String): OldResult
```

## Migration note format

Create `docs/migrations/YYYY-MM-<slug>.md`:

```markdown
# Migration: <short title>

**Released in:** vX.Y.Z  
**Affects:** <CLI | provider name | script execution contract>

## What changed

Short description.

## Before

```kotlin
// old usage
```

## After

```kotlin
// new usage
```

## Why

Rationale for the change.
```

## Provider interface additions

When adding new methods to a provider interface where Koupper owns the only implementation:

1. Add the method to the interface with a clear Kdoc comment.
2. Implement it in all framework implementations (`Local*`, `Aws*`, etc.).
3. Add unit test coverage in the same PR.
4. Record the addition in CHANGELOG.md under the current version section.

This is classified as an additive change and does not require a deprecation notice or major version bump.

## Reference: existing deprecation examples

`IaCProvider` in `providers/src/main/kotlin/com/koupper/providers/iac/IaCProvider.kt` is the canonical example of the `@Deprecated` + structured migration pattern used in this codebase.
