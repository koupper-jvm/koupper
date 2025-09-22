# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),  
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Updated validation logic in `ModuleAnalyzer` to better identify HTTP configuration files.
- Renamed `EXTENSIONS_FOLDER_NAME` property used for locating scripts.
- Updated Octopus.run(...): the method now receives the script path as a parameter.
- Relocated LoggerCore into a dedicated logging module as part of the Koupper architecture.
- Updated the default destination for the Annotation resolver to console.
- `ListenersRegistry`: worker threads inherit the parent `Thread.contextClassLoader` (prevents losing classpath/loggers on replay).
- `JobRunner.runCompiled` now returns the function **result** and uses `trySetAccessible()` when needed.
- `FileJobDriver` logs with `LoggerHolder.LOGGER` instead of `println`.

### Fixed
- Script logs during replay now respect the destination configured by `@Logger` (no longer dumped into `Octopus.Dispatcher`).
- `ClassCastException` caused by `StringBuilder` return in `JobsListenerSetup.run` (now returns `String`).
- `NullPointerException`/`ClassCastException` when reading `@JobsListener(debug = ...)` and `time`: added coercion for `Boolean`/`String`/`Number`.


### Improved
- Removed unnecessary code and cleaned up internal implementations.
- Implemented resolvers to handle scripts via annotation.
- Changed run method to use SignatureDispatcher instead of annotationResolvers.
- Returned response from executed scripts now validates Unit types as a type instead of an object. 
- Added a default LogSpec configuration for Octopus.
- Introduced ScriptLogger to execute scripts within a defined scope.
- Replaced print statements with logger usage in JobLister.
- Improved Logger annotation handling: destination now supports string patterns for file names.
- Change the way to process annotations, now they are validated separately.
- Improved run method implementation for Octopus.
- Enabled logging based on configured properties or annotations. 

### Added
- JobListener support: a JobEvent script can be used as a callback when a job runs.
- Added jobsListenerResolvers map for handling JobListener scripts.
- Added new properties to KouTask.
- Added new env methods to the OS layer.
- Added new methods to ScriptUtilities.
- Implemented logging with @Logger annotation.
- Added script utilities for exported annotation functions.
- Added a logging module to the Koupper Gradle configuration.
- Implemented LoggerHolder to provide a shared LOGGER instance for all objects in OrchestratorExtensions.
- Registered a default logger instance in the container.
- Added new extensions for validating types from string.
- Added support for passing parameters to script execution.
- Added support for executing script sentences.
- `ScriptRunner.runScript(ScriptCall, ScriptEngine, injector): Any?` with:
  - mixed `positionals` + `params` (kv) support
  - JSON-string unwrapping for complex args
  - dependency injection by type and nullable handling
- LogSpec propagation into job replay via `JobsListenerSetup.attachLogSpec(...)` + `JobReplayer.replayJobsListenerScript(...)`.

### Notes
- When parsing annotation text, compiler defaults don’t apply automatically; added safe coercion for `debug` and `time`.
- If your `captureLogs` returns `(logs, result)`, adjust destructuring accordingly.

--

## [5.3.1] - 2025-08-28
### Added
- Support for jobs within Koupper
- Validation schemas
- Common rules for modules

### Improved
- Overall code quality and structure
