# Koupper Examples

This directory contains runnable examples demonstrating Koupper's features.

## Scripts (`scripts/`)

| Example | Feature | File |
|---------|---------|------|
| Basic Export | KSP-processed `@Export` | `basic-export.kts` |
| Scheduled Job | `@Scheduled` with cron | `scheduled-job.kts` |
| Pipeline | `@Pipeline` multi-stage | `pipeline.kts` |
| JWT Auth | Token generation/verification | `jwt-auth-example.kt` |
| Provider Tier | CORE tier ServiceProvider | `provider-tier-example.kt` |

## gRPC (`grpc/`)

| Example | Feature | File |
|---------|---------|------|
| gRPC Client | Bidirectional streaming job submit | `grpc-client-example.kt` |

## REST API (`rest-api/`)

| Example | Feature | File |
|---------|---------|------|
| REST API Usage | HTTP endpoints documentation | `rest-api-example.kt` |

## Running Examples

```bash
# Basic script
koupper run examples/scripts/basic-export.kts

# Scheduled job (requires Octopus daemon)
koupper run examples/scripts/scheduled-job.kts

# gRPC client (requires server on port 9996)
cd examples/grpc && kotlinc -script grpc-client-example.kt
```

## Feature Coverage

- ✅ `@Export` — single entrypoint contract
- ✅ `@Scheduled` — cron/rate/delay scheduling
- ✅ `@Pipeline` — multi-stage job chains
- ✅ gRPC streaming — real-time job dispatch
- ✅ REST API — HTTP script execution
- ✅ JWT Auth — scoped token authentication
- ✅ Provider Tier — CORE/COMMUNITY/EXPERIMENTAL classification
