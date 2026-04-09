# ICP and Productization v1

This document defines the initial product focus for Koupper to maximize adoption, credibility, and monetization potential.

## Ideal Customer Profile (ICP v1)

Primary target:

- Backend/platform teams (3-15 engineers)
- JVM/Kotlin-heavy environments
- High operational scripting load (workers, cron, deploy automations)
- Need stronger reliability and observability than ad-hoc shell scripts

Typical contexts:

- scale-ups with growing platform complexity
- enterprise internal tooling teams with compliance pressure
- teams migrating script sprawl into maintainable automation

## Non-ICP (for now)

- Teams with no JVM/Kotlin footprint and no interest in Kotlin scripting
- Fully no-code automation-first teams that do not maintain code
- Single-use one-off scripting without production expectations

## Positioning statement

Koupper is the easiest way for JVM teams to build script-driven automations that scale to production through a stable runtime contract and provider-first integrations.

## Productization priorities

1. Time-to-first-success under 10 minutes
2. Predictable script execution contract in all surfaces (CLI, worker, runtime routes)
3. Provider-first integrations with explicit env/runtime requirements
4. Production confidence via release gates, smoke checks, and runbooks
5. Community adoption via runnable examples and clear contribution path

## KPI starter set

- Activation: % users that complete quick smoke in first session
- Reliability: smoke pass rate on release branches
- Adoption: weekly `koupper run` + `koupper provider list` usage in demos/internal pilots
- Community: external issues/discussions/PRs per month
- Production proof: number of real teams running Koupper in non-dev environments

## Monetization direction

Short-term:

- implementation and hardening services using Koupper
- architecture packages (automation platform bootstrap, migration from shell scripts)

Mid-term:

- enterprise add-ons (policy, RBAC, audit trail, managed runners)
- support contracts for production operations and upgrades

Long-term:

- curated provider/script hub with quality standards
- managed cloud/control plane experience
