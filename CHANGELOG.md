# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),  
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Updated validation logic in `ModuleAnalyzer` to better identify HTTP configuration files.
- Renamed `EXTENSIONS_FOLDER_NAME` property used for locating scripts.
- Updated Octopus.run(...): the method now receives the script path as a parameter.

### Improved
- Removed unnecessary code and cleaned up internal implementations.
- Implemented resolvers to handle scripts via annotation.
- Changed run method to use SignatureDispatcher instead of annotationResolvers.

### Added
- JobListener support: a JobEvent script can be used as a callback when a job runs.
- Added jobsListenerResolvers map for handling JobListener scripts.
- Added new properties to KouTask.
- Added new env methods to the OS layer.
- Added new methods to ScriptUtilities.
- Implemented logging with @Logger annotation.
- Added script utilities for exported annotation functions.

--

## [5.3.1] - 2025-08-28
### Added
- Support for jobs within Koupper
- Validation schemas
- Common rules for modules

### Improved
- Overall code quality and structure
