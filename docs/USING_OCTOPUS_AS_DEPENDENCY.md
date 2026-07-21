# Using Octopus as a dependency (Maven Local + Lambda)

This guide is for application authors (APIs, workers, Lambda handlers) who depend on
`com.koupper:octopus`, not for end users installing the CLI.

## Two JARs, two jobs

| JAR | How you get it | Where it goes |
|---|---|---|
| **shadowJar** (fat runtime) | `./gradlew :octopus:shadowJar` or standalone installer | `~/.koupper/libs/octopus.jar` — runs the Octopus daemon for `koupper run` |
| **optimized** (library) | `./gradlew :octopus:publishToMavenLocal` | `~/.m2/.../octopus/<version>/` — consumed by Gradle apps |

If you only run the standalone installer, you get the **daemon** JAR.
Your Quizztea / Igly / Lambda project still needs the **library** JAR via Maven.

## Publish to Maven Local

```bash
cd koupper
./gradlew :octopus:publishToMavenLocal -x test
```

Verify:

```bash
ls ~/.m2/repository/com/koupper/octopus/
# expect a folder matching build.gradle version, e.g. 7.2.0
```

## Use it in an application

```gradle
repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("com.koupper:octopus:7.2.0")
}
```

Optional related modules (if published in your setup): `shared`, `logging`, `os`.
Most apps only need `octopus` because the optimized artifact already bundles Koupper modules.

## Lambda checklist

1. `publishToMavenLocal` (or CI that publishes to your private Maven).
2. App depends on `com.koupper:octopus:<version>`.
3. App builds its own fat JAR / zip (handlers + frameworks + Octopus).
4. Deploy **that** artifact — never deploy `~/.koupper/libs/octopus.jar` as the Lambda code package.

## Version alignment

- CLI + engine version should match (`koupper -v`).
- App `implementation("com.koupper:octopus:X.Y.Z")` should match the Octopus you published locally (or the release you consume).
- After cutting a GitHub Release (`vX.Y.Z`), end users install the daemon via `install-standalone.kts`; developers still run `publishToMavenLocal` (or pull from Maven) for app builds.
