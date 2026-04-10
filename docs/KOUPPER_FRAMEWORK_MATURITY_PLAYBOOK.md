# Koupper Framework Maturity Playbook

Purpose: give any new agent a clear, execution-ready plan to continue Koupper as an enterprise framework (without project-specific code).

## 1) Current baseline (already implemented)

- Runtime and orchestration foundation is stable (`run --serve`, daemon cancellation, local process supervision).
- Provider model is active and expanding (typed providers + catalog + consistency checks).
- Infrastructure/reconcile command suite exists:
  - `koupper infra init|validate|plan|apply|drift|output`
  - `koupper reconcile run`
- Stable JSON output contracts are already used for infra/reconcile flows.
- AWS deploy reliability hardening is merged:
  - timeout/retry/backoff controls
  - lambda waiter support
  - frontend backup modes (`full|incremental|disabled`)
  - structured per-action result metadata
- Public docs and runbooks are in place and CI-checked against CLI/provider sync.

## 2) Maturity scorecard (maintainer view)

Use 0-5 scoring (0 = missing, 5 = strong/automated).

| Area | Score | Why it is scored this way |
| --- | --- | --- |
| Runtime orchestration | 4 | strong local supervision + live serve/cancel; keep improving failure telemetry |
| Provider architecture | 4 | good typed boundaries and catalog consistency checks |
| Deploy/reconcile reliability | 4 | retries/waiters/contracts added; still needs longer-lived production metrics |
| Security hardening | 3 | redaction and baseline controls exist; needs formal threat model/checklists |
| Observability | 3 | good operational direction; needs standardized KPIs and dashboards |
| Upgrade/version governance | 3 | changelogs/migrations present; needs stricter deprecation lifecycle |
| Documentation quality | 4 | strong docs + docs CI; keep scenario depth and troubleshooting updates |
| Contributor/agent onboarding | 4 | bootstrap flow is clear; this playbook closes strategic onboarding gap |

## 3) Six-week execution plan (enterprise-ready track)

## Week 1 - Contract stabilization ✓ completed

- ~~Define provider contract versioning policy (additive vs breaking changes).~~ Done — `docs/CONTRACT_VERSIONING_POLICY.md`.
- ~~Add explicit deprecation tags/timelines for CLI/provider APIs.~~ Done — policy + `docs/migrations/` directory with format defined.
- `SecretsClient.delete()` and `SecretsClient.list()` added (additive); tests in `SecretsClientTest`.
- K8s provider timeout now returns structured `K8sResult` (exitCode=124, timedOut=true) instead of throwing; migration note in `docs/migrations/`.
- Deliverable: `docs/CONTRACT_VERSIONING_POLICY.md` + `docs/PROVIDER_AUTHORING_CHECKLIST.md` + checklist updates in MAINTAINER_GUIDE.

## Week 2 - Security formalization

- Publish framework threat model for runtime/CLI/providers.
- Add security checklist for new providers (secrets, retries, least privilege, redaction).
- Deliverable: `docs/SECURITY_THREAT_MODEL.md` + provider authoring checklist integration.

## Week 3 - Reliability observability

- Define framework SLO metrics:
  - deploy success rate
  - rollback rate
  - median reconcile duration
  - transient failure retry success rate
- Add docs for metric collection/report cadence.
- Deliverable: `docs/FRAMEWORK_SLOS.md` and monthly review template.

## Week 4 - Upgrade safety

- Add compatibility matrix (CLI version <-> provider contract expectations).
- Add migration templates for every behavior/default change.
- Deliverable: `docs/COMPATIBILITY_MATRIX.md` + migration template doc.

## Week 5 - Provider DX acceleration

- Add provider scaffold/template workflow (`register + catalog + docs + tests`).
- Add test skeleton for provider contract validation.
- Deliverable: scaffold script/command + updated maintainer docs.

## Week 6 - Production validation loop

- Run 2 real internal pilot projects through full reconcile/deploy flow.
- Capture before/after KPIs and incident outcomes.
- Deliverable: internal case-study note with measured ROI.

## 4) Definition of done for this maturity wave

- Every new provider ships with:
  - catalog registration
  - docs
  - unit/integration coverage
  - security checklist pass
- Every CLI behavior change ships with:
  - migration note
  - docs sync check pass
  - backward compatibility statement
- Reliability KPIs are measured monthly and reviewed in maintainer notes.

## 5) Agent operating checklist (mandatory)

1. Read `docs/AGENT_BOOTSTRAP.md`.
2. Read `docs/NEXT_FEATURES_NOTES.md` and `scripts/release/README.md`.
3. If touching governance/policy, also read `docs/MAINTAINER_GUIDE.md` and this playbook.
4. Branch from `develop` using `feature/*`, `fix/*`, or `docs/*`.
5. Keep scope strict; no unrelated refactors.
6. Run local checks before push:
   - `./scripts/ci/local-quick-checks.ps1 -Target all` (Windows)
   - `./scripts/ci/local-quick-checks.sh all` (Linux/macOS)
7. Use release automation scripts (prefer fast lane).

## 6) Commands reference for delivery

```bash
# open PR quickly on develop with auto-merge
koupper run scripts/release/fast-lane.kts '{"featureBranch":"feature/my-change","enableAutoMerge":true}'

# fallback flow without waiting CI locally
koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-change","waitForCi":false,"mergeAfterCi":false}'
```

## 7) Anti-patterns to avoid

- Do not add project-specific assumptions into framework core/providers.
- Do not change command contracts without migration notes.
- Do not update docs separately from behavior changes.
- Do not bypass release scripts with ad-hoc git/gh flows for standard delivery.
