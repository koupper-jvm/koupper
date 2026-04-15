# Koupper Enterprise Roadmap (Kickoff: 2026-03-30)

This plan extends `ROADMAP_30_60_90.md` with enterprise-focused execution starting Monday, March 30, 2026.

## Scope and relation to the existing roadmap

- `ROADMAP_30_60_90.md` remains the product/adoption north star.
- This document is the operational enterprise track for reliability, observability, and governance.
- Both should run in parallel: product growth + enterprise hardening.
- ICP and productization assumptions are documented in `docs/ICP_PRODUCTIZATION_V1.md`.

## North star for this cycle

By end of June 2026, Koupper should be production-ready for regulated or high-throughput internal workloads, with measurable SLOs and repeatable operating practices.

## 90-day execution windows

### Window 1: Foundation Hardening (2026-03-30 to 2026-04-28)

Outcomes:
- deterministic runtime behavior under load
- baseline enterprise observability
- safer release confidence

Deliverables:
- Job reliability controls: retry policy, timeout policy, dead-letter queue strategy per `configId`
- Correlation ID propagation across CLI -> dispatcher -> worker -> listener
- Structured metrics v1 (queue depth, processing latency, success/failure rates)
- Regression suite for listener/worker logger isolation and MDC nesting
- Release gate: smoke + critical regression checks required before merge to `develop`

### Window 2: Operational Readiness (2026-04-29 to 2026-05-28)

Outcomes:
- faster diagnosis in production incidents
- consistent deployment behavior across Windows/Linux

Deliverables:
- Runtime health endpoints/checks for daemon and queue backends
- Alerting playbook (error budget, high queue depth, worker starvation)
- Operational runbooks (incident triage, stuck queue recovery, safe restart)
- Configuration policy validation (invalid combinations fail fast with actionable messages)
- Compatibility matrix doc: CLI version vs Octopus version expectations

### Window 3: Governance and Scale (2026-05-29 to 2026-06-27)

Outcomes:
- enterprise onboarding and compliance readiness
- predictable scaling and change management

Deliverables:
- Audit trail model for job lifecycle events (dispatch, execution, retry, failure, replay)
- Role-based execution controls (script/module-level permissions roadmap + prototype)
- Performance benchmarks and capacity guide (jobs/minute per profile)
- Versioned migration notes for breaking runtime/CLI behavior
- Enterprise deployment reference (VM/systemd + container baseline)

## Weekly cadence (starting 2026-03-30)

- Monday: commit weekly reliability objective and owner
- Wednesday: ship one hardening increment + one test/regression increment
- Friday: publish status, metrics deltas, and unresolved risks

## Definition of done (enterprise track)

A work item is done only if:
- behavior is covered by regression test or deterministic smoke step
- docs/runbook is updated in same PR
- failure mode and rollback path are explicit
- observability signal exists for the changed path

## Immediate backlog for week 1 (starts 2026-03-30)

1. Add `koupper version` subcommand parity with `-v` and print compatibility hints.
2. Add logger-context regression test that reproduces nested listener/worker logger case.
3. Document queue backend semantics (`file`, `redis`, `sqs`, `database`) with failure behavior.
4. Define SLO v1 for job execution latency and callback completion.
