# Koupper Maintainer Guide

This folder stores maintainer-focused documentation. Public user documentation lives in `koupper-document/docs`.

## Core internal docs

- `DOCUMENTATION_STANDARD.md`: source-of-truth and placement rules for docs.
- `SCRIPT_EXECUTION_CONTRACT.md`: runtime contract for script entrypoints and pipeline usage.
- `PRODUCTION_HARDENING.md`: production baseline for Octopus runtime operations.
- `CLI_COMMAND_CHECKLIST.md`: release-time manual CLI validation checklist.
- `release-versioning.md`: version bump and tagging policy.

## Archive

- `archive/`: historical plans and implementation briefs kept for reference.

## Rule of thumb

- If users should read it, move it to `koupper-document/docs`.
- If maintainers operate the repo with it, keep it here.
