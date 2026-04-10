#!/usr/bin/env bash
set -euo pipefail

MODE="install"
AUTO_INSTALL_DEPS=false
ASSUME_YES=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --doctor|--check)
      MODE="doctor"
      ;;
    --auto-install-deps)
      AUTO_INSTALL_DEPS=true
      ;;
    -y|--yes)
      ASSUME_YES=true
      ;;
    *)
      echo "Unknown option: $1"
      echo "Usage: ./scripts/setup/install.sh [--doctor] [--auto-install-deps] [--yes]"
      exit 2
      ;;
  esac
  shift
done

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

version_ge() {
  local a b
  a="$1"
  b="$2"
  [ "$(printf '%s\n%s\n' "$a" "$b" | sort -V | head -n1)" = "$b" ]
}

confirm_action() {
  local prompt
  prompt="$1"
  if [[ "$ASSUME_YES" == true ]]; then
    return 0
  fi
  printf "%s [y/N]: " "$prompt"
  read -r answer
  [[ "$answer" == "y" || "$answer" == "Y" || "$answer" == "yes" || "$answer" == "YES" ]]
}

install_with_brew() {
  if ! has_cmd brew; then
    fail "Homebrew is required for auto-install on macOS: https://brew.sh"
    return 1
  fi
  brew install "$@"
}

linux_install_cmd() {
  if has_cmd apt-get; then
    if [[ "$ASSUME_YES" == true ]]; then
      echo "sudo apt-get update && sudo apt-get install -y openjdk-17-jdk kotlin"
    else
      echo "sudo apt-get update && sudo apt-get install openjdk-17-jdk kotlin"
    fi
    return 0
  fi
  if has_cmd dnf; then
    if [[ "$ASSUME_YES" == true ]]; then
      echo "sudo dnf install -y java-17-openjdk-devel kotlin"
    else
      echo "sudo dnf install java-17-openjdk-devel kotlin"
    fi
    return 0
  fi
  if has_cmd pacman; then
    if [[ "$ASSUME_YES" == true ]]; then
      echo "sudo pacman -S --noconfirm jdk17-openjdk kotlin"
    else
      echo "sudo pacman -S jdk17-openjdk kotlin"
    fi
    return 0
  fi
  return 1
}

auto_install_deps_if_needed() {
  local java_issue kotlin_issue java_version_raw java_major kotlin_version_raw kotlin_version
  java_issue=""
  kotlin_issue=""
  java_version_raw=""
  java_major=""
  kotlin_version_raw=""
  kotlin_version=""

  if has_cmd java; then
    java_version_raw="$(java -version 2>&1 | head -n 1 || true)"
    java_major="$(java -version 2>&1 | sed -n 's/.* version "\([0-9][0-9]*\).*/\1/p' | head -n 1)"
    if [[ -z "$java_major" || "$java_major" -lt 17 ]]; then
      java_issue="Java 17+ required (detected: ${java_version_raw:-unknown})"
    fi
  else
    java_issue="Java is not available in PATH"
  fi

  if has_cmd kotlinc; then
    kotlin_version_raw="$(kotlinc -version 2>&1 | head -n 1 || true)"
    kotlin_version="$(printf "%s" "$kotlin_version_raw" | sed -n 's/.* \([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\).*/\1/p' | head -n1)"
    if [[ -z "$kotlin_version" ]] || ! version_ge "$kotlin_version" "2.0.0"; then
      kotlin_issue="Kotlin compiler 2.0.0+ required (detected: ${kotlin_version_raw:-unknown})"
    fi
  else
    kotlin_issue="kotlinc is not available in PATH"
  fi

  if [[ -z "$java_issue" && -z "$kotlin_issue" ]]; then
    return 0
  fi

  if [[ "$AUTO_INSTALL_DEPS" != true ]]; then
    return 0
  fi

  warn "Auto-install/update requested for missing or incompatible prerequisites"
  [[ -n "$java_issue" ]] && warn "$java_issue"
  [[ -n "$kotlin_issue" ]] && warn "$kotlin_issue"

  if ! confirm_action "Proceed with automatic dependency install/update?"; then
    warn "Skipping auto-install/update by user choice."
    return 0
  fi

  if [[ "$OS_NAME" == "Darwin" ]]; then
    if [[ -n "$java_issue" ]]; then
      info "Installing/updating Java 17+ with Homebrew..."
      install_with_brew openjdk@17 || install_with_brew --cask temurin@17
    fi
    if [[ -n "$kotlin_issue" ]]; then
      info "Installing/updating Kotlin with Homebrew..."
      install_with_brew kotlin
    fi
    return 0
  fi

  if [[ "$OS_NAME" == "Linux" ]]; then
    if linux_cmd=$(linux_install_cmd); then
      info "Running Linux package manager command for prerequisites..."
      eval "$linux_cmd"
      return 0
    fi
    warn "Auto-install unsupported on this Linux distro/package manager."
    return 0
  fi

  warn "Auto-install is currently supported on macOS/Linux in this script."
}

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

auto_install_deps_if_needed

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
  KOTLIN_VERSION="$(printf "%s" "$KOTLIN_VERSION_RAW" | sed -n 's/.* \([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\).*/\1/p' | head -n1)"
  if [[ -n "$KOTLIN_VERSION" ]] && version_ge "$KOTLIN_VERSION" "2.0.0"; then
    KOTLIN_OK=true
    ok "Kotlin compiler detected ($KOTLIN_VERSION_RAW)"
  else
    HAS_FAIL=true
    fail "Kotlin compiler 2.0.0+ is required (detected: ${KOTLIN_VERSION_RAW:-unknown})"
  fi
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
