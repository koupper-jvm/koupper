# Documentation Standard

This repository uses two documentation surfaces with different responsibilities.

## README role

- `README.md` is the entry pitch and orientation layer.
- Keep it concise: value proposition, quick start, links to canonical docs.
- Do not duplicate full command/provider manuals in README.

## 1) Public docs site (source of truth for users)

Location: `koupper-docs/docs`

Use this for:

- CLI command reference and examples
- Provider catalog and provider-specific setup
- Getting started, architecture walkthroughs, production guides
- Any page that should be linked from GitHub README, docs nav, or onboarding messages

## 2) Repository `/docs` (source of truth for contributors)

Location: `docs/` in this repository

Use this for:

- Maintainer playbooks and internal release workflows
- Implementation briefs and migration plans
- Contributor checklists and engineering notes
- Content not intended as public end-user documentation

Structure:

- `docs/MAINTAINER_GUIDE.md`: maintainer index and quick links.
- `docs/archive/`: historical plans and superseded implementation briefs.

## Rules for new documentation

- User-facing content goes to `koupper-docs/docs` first.
- Internal process/maintenance content stays in this repository `docs/`.
- Avoid duplicating full docs in both places; keep one canonical page and link to it.
- When a command/provider behavior changes, update docs site pages in the same PR/branch.
- Keep examples copy-paste ready and aligned with current CLI/runtime behavior.

## Link policy

- Root `README.md` should stay concise and point to the public docs site.
- Internal docs in this repository may link to public docs pages for context, but should not mirror them line by line.
