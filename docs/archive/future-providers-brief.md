# Koupper Future Providers Brief

Purpose: define a clear backlog of future providers that can be implemented by an AI coding model without losing architecture consistency.

## Context

- Runtime: Koupper scripts + Octopus orchestration.
- DI model: `ServiceProvider` binds a contract interface to implementation(s).
- Discovery: provider must be registered in `ServiceProviderManager` and `providers-catalog.json`.
- Design target: each provider should expose a focused contract, typed request/response models, and operational safety defaults.

## Priority Roadmap

1. `docker`
2. `git`
3. `secrets`
4. `ai-llm-ops`
5. `k8s`
6. `vector-db`
7. `notifications`
8. `observability`
9. `queue-ops`
10. `iac`
11. `runtime-router`
12. `mcp-server`

## Current Implementation Status

Implemented in `koupper` (merged to `develop`):

- `docker`
- `git`
- `secrets`
- `ai-llm-ops`
- `k8s`
- `vector-db`
- `notifications`
- `observability`
- `queue-ops`
- `iac`
- `runtime-router`
- `mcp` (covers `mcp-server` scope in this brief)

Additional implemented provider:

- `n8n` (workflow trigger + execution status polling)

Notes:
- Some providers require local binaries/services to fully execute examples (`docker`, `kubectl`, `terraform`).
- Example scripts are included under `examples/` for each implemented provider.

## Provider Specifications

### 1) Docker Provider (`docker`)

Goal: automate local/containerized workflows from scripts.

Contract ideas:
- `build(request)`
- `run(request)`
- `stop(container)`
- `logs(container, tail)`
- `exec(container, command)`
- `composeUp(file, project)`
- `composeDown(file, project)`

Safety defaults:
- timeouts on all commands
- `failOnNonZeroExit=true` by default
- explicit flags for destructive operations

Env:
- `DOCKER_HOST` (optional)
- `DOCKER_CONTEXT` (optional)

### 2) Git Provider (`git`)

Goal: scripted repository automation with safe defaults.

Contract ideas:
- `status(path)`
- `diff(path, staged)`
- `log(path, limit)`
- `createBranch(path, name)`
- `commit(path, message)`
- `merge(path, source, target)`
- `tag(path, name)`

Safety defaults:
- disallow force operations unless explicit override
- reject commit if no changes
- surface hook failures clearly

Env:
- `GIT_AUTHOR_NAME` (optional)
- `GIT_AUTHOR_EMAIL` (optional)

### 3) Secrets Provider (`secrets`)

Goal: retrieve secrets without hardcoding credentials in scripts.

Backends:
- Vault
- AWS Secrets Manager
- optional local encrypted file

Contract ideas:
- `get(key)`
- `getJson(key)`
- `put(key, value)` (optional)
- `exists(key)`

Safety defaults:
- redact values in logs
- never print secret payloads

### 4) AI LLM Ops Provider (`ai-llm-ops`)

Goal: production AI operations beyond simple prompt calls.

Contract ideas:
- `chat(request)`
- `structured(request, schema)`
- `embed(texts)`
- `rerank(query, docs)`
- `toolCall(request)`

Features:
- model fallback chain
- retries/backoff
- response validation by JSON schema

### 5) Kubernetes Provider (`k8s`)

Goal: scriptable cluster operations for release and ops.

Contract ideas:
- `apply(manifest)`
- `get(kind, name, namespace)`
- `logs(kind, name, namespace, tail)`
- `rolloutStatus(deployment, namespace)`
- `rolloutRestart(deployment, namespace)`

Safety defaults:
- require explicit namespace in mutating ops
- dry-run mode for apply

### 6) Vector DB Provider (`vector-db`)

Goal: RAG-ready vector operations with unified contract.

Backends:
- pgvector
- Qdrant
- Pinecone

Contract ideas:
- `upsert(collection, vectors)`
- `query(collection, vector, topK, filter)`
- `delete(collection, ids)`

### 7) Notifications Provider (`notifications`)

Goal: send operational events to chat/incident channels.

Backends:
- Slack
- Discord
- Teams

Contract ideas:
- `sendText(channel, text)`
- `sendStructured(channel, payload)`
- `sendError(channel, title, details)`

### 8) Observability Provider (`observability`)

Goal: push execution traces and metrics externally.

Contract ideas:
- `emitMetric(name, value, tags)`
- `emitEvent(type, payload)`
- `emitTrace(span)`

Targets:
- OpenTelemetry
- Prometheus Pushgateway
- Loki/ELK compatible log sink

### 9) Queue Ops Provider (`queue-ops`)

Goal: unified queue management across backends.

Backends:
- SQS
- Redis streams
- RabbitMQ

Contract ideas:
- `listPending(queue)`
- `requeue(id)`
- `deadLetter(id)`
- `purge(queue)`

### 10) IaC Provider (`iac`)

Goal: controlled infrastructure automation from scripts.

Contract ideas:
- `terraformPlan(path, vars)`
- `terraformApply(path, vars)`
- `terraformOutput(path)`
- `driftCheck(path)`

Safety defaults:
- require explicit approval flag for apply
- capture and store plan output

### 11) Runtime Router Provider (`runtime-router`)

Goal: create HTTP endpoints directly from scripts and deploy lightweight API runtimes without bootstrapping a full module.

Target DX:

```kotlin
rProvider.registerRouter {
    path { "/users" }

    post<String, Int> {
        path { "/create" }
        middlewares { listOf("jwt-auth") }
        script {
            { input ->
                200
            }
        }
    }
}
```

Contract ideas:
- `registerRouter(builder)`
- `start(port, host)`
- `stop(routerId)`
- `deploy(request)`
- `routes()`

Backend options:
- Grizzly (first implementation)
- Ktor engine adapter (optional second implementation)

Safety defaults:
- route conflict detection
- explicit middleware registration
- strict JSON schema typing for script input/output

### 12) MCP Server Provider (`mcp-server`)

Goal: expose script-defined tools as an MCP-compatible server for AI clients.

Contract ideas:
- `registerTool(name, schema, handler)`
- `registerResource(name, uriTemplate, handler)`
- `start(transport = "stdio" | "http")`
- `stop(serverId)`
- `describe()`

Features:
- tool input validation by JSON schema
- timeout/cancellation support per tool invocation
- optional auth for HTTP transport

Safety defaults:
- deny-all network egress mode unless explicitly enabled
- per-tool timeout and max payload size
- audit logging of tool invocations (without secret values)

## Shared Engineering Rules for Any New Provider

- Contract-first design (`interface` + typed models).
- Fail with actionable error messages.
- Add provider entry in `providers-catalog.json` with env docs.
- Register provider in `ServiceProviderManager`.
- Include at least one executable example script under `examples/`.
- Prefer explicit timeouts and non-destructive defaults.
- Add minimal tests for happy path + failure path.
- For runtime providers, define lifecycle APIs (`start`, `stop`, health/status).

## Definition of Done (Provider)

- Compiles in module (`:providers:compileKotlin` pass).
- Public contract documented (Markdown page in docs repo).
- Example script works with real env.
- Provider discoverable via:
  - `koupper provider list`
  - `koupper provider info <id>`

## AI Implementation Prompt Seed

Use this brief as source of truth and ask the model to:

1. Implement one provider at a time.
2. Keep changes scoped to provider + registration + catalog + examples.
3. Avoid breaking existing provider contracts.
4. Return a concise summary of contract, env vars, and example usage.
