# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),  
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

- No changes yet.

---

## [4.7.1] - 2026-03-28

### Changed
- `deploy` now requires auth token configuration (`KOUPPER_OCTOPUS_TOKEN` / `koupper.octopus.token`).
- `deploy` includes payload SHA-256 checksum for daemon-side integrity verification.

### Added
- Deploy smoke coverage improvements for auth-prefixed socket exchange and checksum propagation.

---

## [4.6.0] - 2026-03-28

### Added
- End-to-end smoke tests for `run` socket flows covering JSON responses, requestId filtering, and legacy envelope fallback.

### Changed
- `run` command now supports runtime Octopus host/port overrides via:
  - `KOUPPER_OCTOPUS_HOST` / `koupper.octopus.host`
  - `KOUPPER_OCTOPUS_PORT` / `koupper.octopus.port`
- Help and runtime docs were updated to include socket override usage.
- Updated help descriptions related to the `new` command.
- Refactored `ModuleCommand` to improve how it displays module information.
- Modified the structure of `ApiConfig` to support the new format.
- The `module` command now generates a default HTTP configuration for script types: `HANDLERS_CONTROLLERS_SCRIPTS`.
- Updated CLI arguments for `JobCommand` (new flags, defaults, and help text).

---

## [4.5.0] - 2025-08-28
### Added
- New `job` command in CLI

### Improved
- Updated command menu copies and help texts
- Minor fixes and refactors
