# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),  
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Added framework-level IaC execution contract with stable stage output (`ok`, `stage`, `exitCode`, `durationMs`, `warnings`, `errors`, `artifacts`, `nextAction`).
- Added Terraform lifecycle methods to IaC provider (`init`, `validate`, `plan`, `apply`, `drift`, `output`) with retries/timeouts.
- Added drift-spec v1 evaluator with support for `required_only` and `exact_match` checks across Dynamo, API, Lambda, SQS, and workers.
- Added AWS deploy action envelope metadata for stage-level outcomes (`ok`, `action`, `exitCode`, `durationMs`, `attempts`, `warnings`, `errors`, `nextAction`).

### Changed
- Module scaffolding bootstrapping now normalizes artifact type aliases (`scripts/jobs/pipelines`) before generating starter execution flow.
- Local template source resolution now prioritizes versioned `templates/model-project` paths ahead of legacy `model-project` fallback paths.
- Hardened `AwsCliDeployProvider` with configurable timeout/retry/backoff controls, Lambda update waiters, and static-site backup strategy modes (`full`, `incremental`, `disabled`).

### Fixed
- Local template extraction now normalizes single-root zip archives so scaffold files land at the expected module root.
- Script module bootstrapping now uses property references (`::myScript`) in `processManager.call(...)` to match `ScriptExecutor` API expectations.
- Script argument type resolution now infers script host class names when explicit metadata is missing, reducing runtime `argument type mismatch` for inline script models.
- Script argument type resolution now handles fully-qualified inline model names by falling back to script-local nested class lookup.
- Job/script argument binding now prefers parameter types extracted from source signatures when runtime metadata contains fully-qualified aliases.

---

## [6.3.1] - 2026-03-28

### Changed
- Hardened DEPLOY execution by requiring daemon token-backed auth for remote deploy flows.
- Added payload integrity validation using SHA-256 checksum correlation.
- Added configurable max deploy payload guardrail (`KOUPPER_OCTOPUS_DEPLOY_MAX_BYTES`, default `262144`).

### Added
- Extended socket integration coverage for DEPLOY auth, checksum mismatch, and payload size rejection scenarios.

## [6.2.0] - 2026-03-28

### Added
- Socket observability and protocol coverage improvements for request/response handling and integration tests.
- Safer parser and socket behavior validation to keep CLI/daemon communication stable under mixed payloads.

### Changed
- Octopus protocol parsing now relies on Jackson-based request decoding in key paths.
- Runtime/daemon test suites were stabilized for environment-dependent scenarios.

### Fixed
- Stdout/session routing race conditions in Octopus socket execution flow.
- Socket security defaults were hardened (loopback-first, tighter run-from-url handling).
- Multiple unchecked-cast and parser edge-case regressions found during protocol hardening.

## [6.0.0] - 2025-10-15

### Added
- **Java 17 support**: full compatibility with JDK 17 for all modules.
- **Windows batch utilities**: added `koupper.bat` and `start-octopus.bat` for easier CLI execution on Windows environments.
- **New service providers**:
  - Added `AIProvider` to support requests to OpenAI and other AI APIs.
  - Expanded provider layer for dynamic integrations.
- **JobListener support**: a JobEvent script can now be used as a callback when a job runs.
- **jobsListenerResolvers** map introduced for handling JobListener scripts.
- Added new properties to `KouTask`.
- Added new environment (`env`) methods in the OS layer.
- Added new utilities in `ScriptUtilities`.
- Implemented logging with `@Logger` annotation.
- Added a dedicated **logging module** to the Koupper Gradle configuration.
- Implemented `LoggerHolder` to provide a shared `LOGGER` instance across modules.
- Added default `LogSpec` configuration for Octopus.
- Added JSON helper: `JSONFileHandler.toType()` in `JsonShortcuts.kt`.
- Introduced `ScriptLogger` for scoped log execution in scripts.
- Added support for passing parameters to scripts and executing inline script sentences.

### Changed
- **Core architecture upgrade for jobs**:
  - Jobs configuration can now be defined per function or at the project level.
  - `forEachPending` now returns a list of `JobResult` objects.
  - `JobLister.list` now returns a list of `Any` objects.
- **Scripting engine replaced**:
  - Migrated from the previous `Jsr223ScriptingHost` to **`BasicJvmScriptingHost`** for better performance and native Kotlin handling.
- Updated validation logic in `ModuleAnalyzer` to better identify HTTP configuration files.
- Renamed `EXTENSIONS_FOLDER_NAME` property used for locating scripts.
- `Octopus.run(...)` now receives the script path as a parameter.
- Relocated `LoggerCore` into a dedicated logging module.
- Updated the default destination for the annotation resolver to `console`.
- `ListenersRegistry`: worker threads now inherit the parent `Thread.contextClassLoader` (prevents losing classpath/loggers on replay).
- `JobRunner.runCompiled` now returns the function **result** and uses `trySetAccessible()` when needed.
- `FileJobDriver` now logs via `LoggerHolder.LOGGER` instead of `println`.
- Set `file` as default work driver for JobListener setup.
- SQS configuration now falls back to IAM credentials if environment variables are not set.

### Fixed
- Script logs during replay now respect the destination configured by `@Logger` (no longer dumped into `Octopus.Dispatcher`).
- Fixed `ClassCastException` caused by `StringBuilder` return in `JobsListenerSetup.run` (now returns `String`).
- Fixed `NullPointerException`/`ClassCastException` when reading `@JobsListener(debug = ...)` and `time`: added coercion for `Boolean`/`String`/`Number`.

### Improved
- General cleanup of unused or redundant code across modules.
- Implemented resolvers to handle scripts via annotation.
- Changed `run` method to use `SignatureDispatcher` instead of `annotationResolvers`.
- Returned response from executed scripts now validates `Unit` as a type instead of an object.
- Improved `Logger` annotation handling: destination now supports string patterns for file names.
- Changed annotation processing flow: validation is now handled independently per annotation.
- Improved internal `run` method implementation for Octopus.
- Enhanced logging system based on configured properties or annotations.

### Notes
- When parsing annotation text, compiler defaults don’t apply automatically; safe coercion was added for `debug` and `time`.
- If your `captureLogs` returns `(logs, result)`, adjust destructuring accordingly.

---

## [5.3.1] - 2025-08-28
### Added
- Support for jobs within Koupper.
- Validation schemas.
- Common rules for modules.

### Improved
- Overall code quality and structure.
