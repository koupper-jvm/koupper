# Script Execution Contract v1

This contract defines predictable script execution behavior for Koupper/Octopus.

## Entrypoint rule

- A `.kts` runtime script must declare exactly one `@Export` entrypoint.
- Recommended name for the entrypoint: `setup`.
- If no `@Export` is found, runtime fails.
- If more than one `@Export` is found, runtime fails with a clear error.

## Recommended pattern

Use one exported entrypoint and keep all step functions internal.

```kotlin
@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    // orchestrate all work from here
    mapOf("ok" to true)
}
```

## Pipeline pattern

- Use `ScriptExecutor.runPipeline(...)` from inside `setup`.
- Use `dependsOn(...)` only with property references (`::myStep`).
- For dependency graphs, use `async = false`.

## Provider-first rule

- Cloud and infrastructure actions should be performed through Service Providers from the container.
- Avoid direct SDK/CLI integrations inside scripts when an equivalent provider exists.
- Local build commands (gradle/npm) are acceptable outside providers when they are project-local build concerns.

## Migration notes

- Scripts with multiple exports must be migrated to a single `setup` entrypoint.
- Keep reusable logic in internal functions/properties, not additional exports.
