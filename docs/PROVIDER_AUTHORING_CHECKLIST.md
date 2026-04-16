# Provider Authoring Checklist

This checklist must be completed for every new Service Provider shipped in the framework. It covers the four required surfaces: register, catalog, docs, and tests.

## 1 — Define the contract interface

- [ ] Create `<Name>Provider.kt` with a Kotlin `interface` defining the public contract.
- [ ] Keep method signatures stable — follow `docs/CONTRACT_VERSIONING_POLICY.md` before adding breaking parameters.
- [ ] Use structured result types (data classes) instead of throwing exceptions for operational failures. Return a result object with an `ok: Boolean`, `exitCode`, and diagnostic fields.
- [ ] If the provider wraps an external CLI tool, use a configurable `commandRunner` lambda parameter to allow test injection (see `TerraformIaCProvider` as the canonical example).

## 2 — Write the implementation

- [ ] Create `<Name>ServiceProvider.kt` extending `ServiceProvider`.
- [ ] Implement `override fun up()` binding the interface class to the implementation via `app.bind(...)`.
- [ ] Read configuration from environment variables via `env(name, required, default)`. Never hardcode credentials or hostnames.
- [ ] Redact sensitive values (tokens, passwords, secrets) before including them in result metadata or log output.

## 3 — Register in ServiceProviderManager

- [ ] Add `import` for the new service provider in `ServiceProviderManager.kt`.
- [ ] Add the class reference to `listProviders()` return list.

## 4 — Add to providers-catalog.json

Add an entry to `koupper/providers/src/main/resources/providers-catalog.json`:

```json
{
  "id": "<short-id>",
  "serviceProvider": "<ClassName>",
  "description": "One sentence describing what this provider does.",
  "bindings": [
    {
      "contract": "<InterfaceName>",
      "implementations": [
        { "class": "<ImplClassName>", "tag": "<optional-tag>" }
      ]
    }
  ],
  "env": [
    { "name": "ENV_VAR_NAME", "required": true, "description": "What this env var controls." }
  ],
  "docs": "https://koupper.com/providers/<short-id>.html"
}
```

## 5 — Write tests

- [ ] Create a unit test file at `providers/src/test/kotlin/com/koupper/providers/<name>/<Name>ProviderTest.kt`.
- [ ] Test the provider interface contract (happy path).
- [ ] Test edge cases: missing required config, empty/null inputs, error/failure paths.
- [ ] For CLI-wrapping providers: use the `commandRunner` injectable to avoid real subprocess calls in tests (see `IaCProviderTest` and `AwsDeployServiceProviderTest`).
- [ ] Run locally: `cd koupper && ./gradlew :providers:test --tests "com.koupper.providers.<package>.<Name>ProviderTest"`

## 6 — Write public docs

- [ ] Create `koupper-docs/docs/providers/<short-id>.md` with:
  - Purpose and use cases
  - Environment variables table
  - Usage example in a `.kts` script
  - Link to any runnable example in `examples/`

## 7 — Run consistency checks

```bash
# Core catalog parity
cd koupper && ./gradlew :providers:test --tests "com.koupper.providers.ProviderCatalogConsistencyTest"

# CLI catalog parity (if docs exist)
cd koupper-cli && ./gradlew test --tests "com.koupper.cli.commands.ProviderCommandCatalogPathTest"
```

Both tests must pass before opening a PR.

## 8 — Update CHANGELOG.md

Add an entry under the current unreleased version in `CHANGELOG.md`:

```markdown
### Added
- `<NameServiceProvider>`: <one-line description of what it provides>
```

---

## Scaffold file structure

```
koupper/providers/src/main/kotlin/com/koupper/providers/<name>/
├── <Name>Provider.kt          # interface + data classes
└── <Name>ServiceProvider.kt   # ServiceProvider.up() binding

koupper/providers/src/test/kotlin/com/koupper/providers/<name>/
└── <Name>ProviderTest.kt      # unit tests

koupper-docs/docs/providers/
└── <short-id>.md              # public user docs
```
