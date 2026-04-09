#!/usr/bin/env bash
set -euo pipefail

target="${1:-all}"

run_core() {
  echo "[ci] Running core/providers targeted checks"
  (
    cd koupper
    ./gradlew :providers:test --tests "com.koupper.providers.ProviderCatalogConsistencyTest" --tests "com.koupper.providers.command.CommandRunnerServiceProviderTest"
  )
}

run_cli() {
  echo "[ci] Running CLI targeted checks"
  (
    cd koupper-cli
    ./gradlew test --tests "com.koupper.cli.commands.ProviderCommandCatalogPathTest"
  )
}

run_docs() {
  echo "[ci] Running docs checks/build"
  (
    cd koupper-document
    npm run docs:check
    npm run docs:build
  )
}

case "$target" in
  core)
    run_core
    ;;
  cli)
    run_cli
    ;;
  docs)
    run_docs
    ;;
  all)
    run_core
    run_cli
    run_docs
    ;;
  *)
    echo "Usage: scripts/ci/local-quick-checks.sh [core|cli|docs|all]"
    exit 2
    ;;
esac

echo "[ci] Quick checks completed for target: $target"
