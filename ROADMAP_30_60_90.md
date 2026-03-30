# Koupper 30/60/90 Day Roadmap

Enterprise execution add-on (kickoff 2026-03-30): see `ROADMAP_ENTERPRISE_2026_Q2.md`.

## Why this helps

This roadmap turns your work into an execution plan that helps you:

- Prioritize what actually grows adoption (not just more features).
- Ship in phases with measurable outcomes.
- Communicate direction to collaborators/users clearly.
- Keep releases, docs, and product quality aligned.

In short: it helps convert a strong codebase into a product people can adopt and trust.

## Goal

Turn Koupper from a strong technical framework into a clearly positioned, adoptable product with repeatable growth signals (users, usage, and successful production stories).

## Phase 1 (Days 1-30): Positioning + Adoption Foundation

Primary objective: define who Koupper is for, what it solves better than alternatives, and make first use successful in <=10 minutes.

### Deliverables

1. Positioning
   - One-line value proposition.
   - ICP v1 (ideal customer profile), e.g., backend/platform teams running jobs/cron/workers.
   - Top 3 pain points solved today.

2. Docs baseline
   - 5-minute quickstart.
   - "Architecture in 2 minutes".
   - CLI <-> Octopus compatibility matrix.

3. Golden demo #1 (production-shaped)
   - Queue worker demo with retry + logs + metrics + failure path.
   - Expected output + validation command.

4. Stability guardrails
   - Repeatable release/tag flow.
   - "Known limitations" section.

### Success metrics

- New user can run quickstart without manual debugging.
- 1 complete end-to-end example is runnable and test-backed.
- Release process is documented and used.

## Phase 2 (Days 31-60): Production Readiness + Credibility

Primary objective: make Koupper feel safe for real workloads and easy to operate.

### Deliverables

1. Golden demos #2 and #3
   - Scheduled automation demo (cron) with alert/error flow.
   - HTTP/service-provider demo (DB + auth + validation).

2. Ops and observability docs
   - Structured logging guide.
   - Health/readiness checklist.
   - Socket/runtime troubleshooting playbook.

3. Deployment docs
   - Docker runbook.
   - Linux systemd service example.
   - Optional minimal k8s recipe.

4. Reliability improvements
   - Harden timeout/retry defaults.
   - Smoke checks for startup/run/failure path.
   - Protocol edge-case regression tests.

5. Distribution improvements
   - Versioned artifact retrieval is straightforward.
   - Predictable release note template every release.

### Success metrics

- 3 complete demos are available and passing.
- New user can deploy locally/VM with docs only.
- Fewer recurring setup issues in tracker.

## Phase 3 (Days 61-90): Growth + Community Flywheel

Primary objective: create repeatable inbound interest and early external contributions.

### Deliverables

1. Public roadmap + contribution path
   - Quarterly roadmap.
   - Beginner-friendly labels.
   - Contribution setup in <=10 steps.

2. Content engine
   - 3 short demo videos (2-4 min).
   - 2 technical before/after posts with metrics.
   - 1 architecture deep-dive post.

3. Proof points
   - At least one case study.
   - Baseline benchmarks (startup time, latency, recovery behavior).

4. Product polish
   - Better error messages with actionable hints.
   - Better first-run/common-failure CLI UX.
   - Better onboarding templates/scripts.

### Success metrics

- External feedback loop exists (issues/discussions/PRs).
- At least one real usage story is documented.
- Contribution and release workflows are stable and repeatable.

## Weekly operating rhythm (recommended)

- Monday: choose top 3 outcomes.
- Midweek: ship one demo/docs/reliability increment.
- Friday: changelog update + short progress post.
- Every 2-4 weeks: release with tags and notes.

## What to avoid

- Adding many features without tightening onboarding/docs.
- Supporting too many personas early.
- Letting release discipline slip while complexity grows.

## North star

"Make Koupper the easiest way for a JVM team to run production-grade scripts/workers/automations with confidence, visibility, and low operational overhead."
