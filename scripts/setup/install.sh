#!/usr/bin/env bash
set -euo pipefail

MODE="install"
if [[ "${1:-}" == "--doctor" || "${1:-}" == "--check" ]]; then
  MODE="doctor"
fi

OS_NAME="$(uname -s 2>/dev/null || echo unknown)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RESET='\033[0m'

ok() { printf "${GREEN}[OK]${RESET} %s\n" "$1"; }
warn() { printf "${YELLOW}[WARN]${RESET} %s\n" "$1"; }
fail() { printf "${RED}[FAIL]${RESET} %s\n" "$1"; }
info() { printf "${BLUE}[*]${RESET} %s\n" "$1"; }

has_cmd() { command -v "$1" >/dev/null 2>&1; }

print_requirements_help() {
  cat <<'EOF'

Missing prerequisites detected.

Required:
- Java 17+
  Download: https://adoptium.net/temurin/releases/
- Kotlin compiler (kotlinc)
  Download: https://kotlinlang.org/docs/command-line.html

Optional but recommended:
- Git
  Download: https://git-scm.com/downloads

After installing prerequisites, open a new terminal and run again:
  ./scripts/setup/install.sh
EOF
}

JAVA_OK=false
KOTLIN_OK=false
HAS_FAIL=false

if has_cmd java; then
  JAVA_VERSION_RAW="$(java -version 2>&1 | head -n 1 || true)"
  JAVA_MAJOR="$(java -version 2>&1 | sed -n 's/.* version "\([0-9][0-9]*\).*/\1/p' | head -n 1)"
  if [[ -n "$JAVA_MAJOR" ]] && [[ "$JAVA_MAJOR" -ge 17 ]]; then
    JAVA_OK=true
    ok "Java detected ($JAVA_VERSION_RAW)"
  else
    HAS_FAIL=true
    fail "Java 17+ is required (detected: ${JAVA_VERSION_RAW:-unknown})"
  fi
else
  HAS_FAIL=true
  fail "Java is not available in PATH"
fi

if has_cmd kotlinc; then
  KOTLIN_VERSION_RAW="$(kotlinc -version 2>&1 | head -n 1 || true)"
  KOTLIN_OK=true
  ok "Kotlin compiler detected ($KOTLIN_VERSION_RAW)"
else
  HAS_FAIL=true
  fail "kotlinc is not available in PATH"
fi

if has_cmd git; then
  ok "Git detected ($(git --version 2>/dev/null || echo unknown))"
else
  warn "Git not found in PATH (only required if you clone/update repositories from CLI)"
fi

if [[ "$OS_NAME" == "Darwin" ]]; then
  info "macOS detected"
fi

if [[ "$HAS_FAIL" == true ]]; then
  print_requirements_help
  exit 1
fi

if [[ "$MODE" == "doctor" ]]; then
  info "Prerequisites are healthy. Running Koupper install doctor..."
  kotlinc -script install.kts -- --doctor
  exit 0
fi

info "Prerequisites are healthy. Running Koupper installer..."
kotlinc -script install.kts -- --force
