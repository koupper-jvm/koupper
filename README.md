<div align="center">
  <h1>🐙 Koupper Framework</h1>
  <p><strong>The Ultimate Event-Driven Kotlin Scripting Ecosystem</strong></p>
</div>

<p align="center">
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Architecture-Event--Driven-FF5722?style=for-the-badge" alt="Event Driven" />
  <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="License MIT" />
</p>

---

**Koupper** is an advanced, un-opinionated scripting infrastructure built natively on top of the Kotlin VM. Conceived to eliminate boilerplate and unify enterprise deployments, it provides an industrial-grade Dependency Injection engine, out-of-the-box Cron Daemons, and reactive message handlers out of pure `.kts` files.

Write your business logic in clean scripts, let our **Octopus Engine** handle the reflection and lifecycle, and scale your automations effortlessly.

---

## 🔥 Core Features

- **🚀 Zero-Boilerplate Daemons**: Use the `@Scheduled` annotation to spin up infinite background workers governed by CRON expressions.
- **⚡ Reactive Queue Listeners**: Deploy active consumers monitoring your internal message bus automatically via `@JobsListener`.
- **💉 Native Dependency Injection**: Access internal singletons and providers (like `JSONFileHandler`, `Sender`, or `AI`) anywhere using the global `app.getInstance()` locator.
- **🛡️ Strict JSON Deserialization**: Koupper's CLI layer maps raw, nested JSON arrays directly into your underlying Kotlin Data Classes. No manual parsing required.
- **⚙️ Integrated Sandbox Execution**: Build entire integration tests parsing, asserting, and evaluating sibling scripts dynamically via `ScriptExecutor`.

## 🏗️ Architecture

What you are looking at is not a traditional "script runner"; it is a **Dynamic Execution Orchestrator**.

Koupper operates as a seamless Monorepo split into two symbiotic layers:

1. **The Server-CLI Decoupling (Octopus Engine)**: A long-living JVM daemon responsible for maintaining context, classpaths, handling Dependency Injection, and executing `.kts` scripts in isolated `ClassLoaders`. 
2. **Koupper CLI**: The lightweight binary proxy that communicates interactively with the Octopus socket (`Port 9998`). The CLI acts merely as a pure transmitter, offloading all heavy JVM lifting to the persistent service.

### 🧠 Core Engineering Feats
- **The Depth Tokenizer**: Achieving a CLI that understands `{...}` as a single argument without the OS Shell destroying the structure is handled by a custom manual state-parser engine. Elegant and extremely resilient.
- **Dynamic Unmarshalling Injection**: Using Kotlin Reflection to dynamically map strictly-typed complex Data Classes (like `User`, `SalesReport`, etc.) directly from a raw shell text-string bypasses the `Shell -> JVM` barrier flawlessly without external boilerplate libraries.

---

## ⚡ Setup & Installation

Koupper boots itself using its own ecosystem. To install the CLI, Jars, and Daemon directly on your machine from the source code:

```powershell
# 1. Clone the Monorepo
git clone https://github.com/koupper/koupper.git
cd koupper

# 2. Run the self-bootstrapper (Compiles source and provisions ~/.koupper/bin)
kotlinc -script install.kts
```

**After execution, just ensure `~/.koupper/bin` is in your system `PATH`.**

---

## 🛠️ Usage & Examples

Here are some of the patterns you can build in seconds. Check our `/examples` folder for the fully-commented source code!

### 1. Synchronous Execution (`hello-world.kts`)
Run immediate terminal outputs parsing positional parameters cleanly.
```powershell
koupper run examples/hello-world.kts "Developer"
```

### CLI Socket Overrides
When Octopus is running on a non-default host/port (or token-protected), configure the CLI at runtime:

```powershell
# Host/port overrides
$env:KOUPPER_OCTOPUS_HOST="127.0.0.1"
$env:KOUPPER_OCTOPUS_PORT="9998"

# Optional auth token
$env:KOUPPER_OCTOPUS_TOKEN="your-token"

koupper run examples/hello-world.kts
```

Equivalent JVM properties are also supported:

```powershell
$env:JAVA_TOOL_OPTIONS="-Dkoupper.octopus.host=127.0.0.1 -Dkoupper.octopus.port=9998 -Dkoupper.octopus.token=your-token"
koupper run examples/hello-world.kts
```

### 2. Deep JSON Data-Mapping (`cli-report-generator.kts`)
Send heavily nested HTTP-like JSON objects right from Powershell. The engine will deserialize the payload, map it to a complex `SalesReportCommand` object, and execute the closure.
```powershell
koupper run examples/cli-report-generator.kts '{"reportName": "Q3", "region": "Global", "items": [{"name": "License", "value": 99.0}]}'
```

### 3. Background Cron Daemons (`disk-cleanup-daemon.kts`)
Want to delete old logs at midnight? Just annotate your export and the Octopus Engine will schedule it natively in the background upon booting.
```kotlin
import com.koupper.octopus.annotations.Scheduled
import com.koupper.octopus.annotations.Logger

@Export
@Logger(destination = "file:disk-maintenance-[yyy-MM-dd]")
@Scheduled(debug = false, cron = "0 0 * * *")
val nightlyLogCleanup: () -> Unit = {
    // Business logic runs silently every midnight!
}
```

### 4. Background Workers (`ai-email-worker.kts`)
Wake up instantly when a payload drops in the internal `jobs.json` queue. Features automated logging and error state rotation.
```kotlin
@Export
@JobsListener(debug = true, configId = "customer-support")
val processSupportTicket: (JobEvent, SupportTicket) -> Int = { event, ticket ->
    val sender = app.getInstance(Sender::class)
    sender.sendTo(ticket.userEmail).withContent("We received your ticket!").send()
    200 // Returning 200 marks the job as SUCCESS.
}
```

---

## 🧪 Integration Testing natively

Koupper ships with its own Integration Testing suite capabilities. You can dynamically orchestrate and assert script evaluations directly natively:
```powershell
koupper run examples/integration-tests.kts
```

---

## 🚢 Versioning & Release Workflow

Koupper uses Semantic Versioning with independent artifact tracks:

- `koupper` (Octopus runtime): `6.x.y`
- `koupper-cli` (CLI binary): `4.x.y`

Recommended production release workflow:

1. Merge release-ready PRs into `main`.
2. Bump versions in:
   - `koupper/build.gradle`
   - `koupper-cli/build.gradle`
3. Update changelogs:
   - `CHANGELOG.md`
   - `koupper/CHANGELOG.md`
   - `koupper-cli/CHANGELOG.md`
4. Create annotated git tags:
   - `octopus-v<version>`
   - `cli-v<version>`
   - optional `koupper-v<version>` for a full monorepo snapshot
5. Publish GitHub releases from those tags.

Compatibility note: keep CLI and Octopus tags listed together in release notes for predictable upgrades.

---

## 📜 License
Built with 🩵 by Jacob Guzman Acosta / Igly technologies.
Available under the MIT License.
