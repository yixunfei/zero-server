#!/usr/bin/env bash
set -euo pipefail

# zeroServer cross-platform thin entrypoint. It delegates behavior to existing Java tools.
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_CMD="${MAVEN_CMD:-${ZERO_MAVEN_CMD:-}}"

usage() {
  cat <<'EOF'
zeroServer unified entrypoint
Usage: scripts/zero.sh <command> [options]
Commands:
  doctor                         Check Java, Maven, Git and repository paths
  init [options]                 Install SNAPSHOT and generate/run local prototype
  generate [options]             Generate a project with NewLocalGame
  test [--projectDir DIR] [args] Run repository or generated-project tests
  diagnose --projectDir DIR      Inspect a generated project without running it
  run --projectDir DIR           Run foreground demo; nonzero on failure
  demo --projectDir DIR          Alias for run
  serve --projectDir DIR         Start generated Server and wait for readiness
  status                         Report verified JVM identity and readiness
  stop                           Request graceful shutdown and wait
  help                           Show this help
EOF
}

java_cmd() { printf '%s/bin/java' "${JAVA_HOME:-$(dirname "$(dirname "$(command -v java)")")}"; }
is_windows_host() { case "$(uname -s 2>/dev/null || true)" in MINGW*|MSYS*|CYGWIN*) return 0;; esac; return 1; }
mvn_cmd() {
  if [[ -n "$MAVEN_CMD" ]]; then printf '%s' "$MAVEN_CMD"; return; fi
  if is_windows_host; then
    if [[ -f "$ROOT_DIR/mvnw.cmd" ]]; then printf '%s' "$ROOT_DIR/mvnw.cmd"; return; fi
    command -v mvn.cmd >/dev/null 2>&1 || { printf 'zero: Maven unavailable; use mvnw.cmd or install Maven 3.9+\n' >&2; exit 1; }
    printf '%s' mvn.cmd
    return
  fi
  if [[ -x "$ROOT_DIR/mvnw" ]]; then printf '%s' "$ROOT_DIR/mvnw"; return; fi
  command -v mvn >/dev/null 2>&1 || { printf 'zero: Maven unavailable; use ./mvnw or install Maven 3.9+\n' >&2; exit 1; }
  printf '%s' mvn
}
run_java_tool() {
  (cd "$ROOT_DIR" && ZERO_MAVEN_CMD="$(mvn_cmd)" "$(java_cmd)" "$@")
}

require_root() {
  [[ -f "$ROOT_DIR/pom.xml" && -f "$ROOT_DIR/scripts/ZeroLocalDoctor.java" ]] || {
    printf 'zero: run this command from the zeroServer checkout or its scripts directory\n' >&2
    exit 2
  }
}

doctor() { require_root; run_java_tool scripts/ZeroLocalDoctor.java; }

init_cmd() {
  require_root
  run_java_tool scripts/RunLocalPrototype.java "$@"
}

generate_cmd() {
  require_root
  run_java_tool scripts/NewLocalGame.java "$@"
}

test_cmd() {
  require_root
  local project_dir=""
  local args=()
  while (($#)); do
    if [[ "$1" == "--projectDir" ]]; then
      [[ $# -ge 2 ]] || { printf 'zero: --projectDir requires a value\n' >&2; exit 2; }
      project_dir="$2"; shift 2
    else
      args+=("$1"); shift
    fi
  done
  if [[ -n "$project_dir" ]]; then
    local project_pom; project_pom="$(cd "$project_dir" && pwd)/pom.xml"
    if ((${#args[@]})); then
      (cd "$ROOT_DIR" && "$(mvn_cmd)" -B -ntp -f "$project_pom" test "${args[@]}")
    else
      (cd "$ROOT_DIR" && "$(mvn_cmd)" -B -ntp -f "$project_pom" test)
    fi
  else
    if ((${#args[@]})); then
      (cd "$ROOT_DIR" && "$(mvn_cmd)" -B -ntp test "${args[@]}")
    else
      (cd "$ROOT_DIR" && "$(mvn_cmd)" -B -ntp test)
    fi
  fi
}

diagnose_cmd() {
  require_root
  local project_dir=""
  while (($#)); do
    case "$1" in
      --projectDir) [[ $# -ge 2 ]] || { printf 'zero: --projectDir requires a value\n' >&2; exit 2; }; project_dir="$2"; shift 2;;
      *) printf 'zero: unknown diagnose option: %s\n' "$1" >&2; exit 2;;
    esac
  done
  [[ -n "$project_dir" ]] || { printf 'zero: diagnose requires --projectDir DIR\n' >&2; exit 2; }
  run_java_tool scripts/InspectLocalScaffold.java --projectDir "$project_dir"
}

process_cmd() {
  require_root
  run_java_tool scripts/ZeroProcess.java "$@"
}

command_name="${1:-help}"; [[ $# -gt 0 ]] && shift
case "$command_name" in
  doctor) doctor "$@";;
  init) init_cmd "$@";;
  generate) generate_cmd "$@";;
  test) test_cmd "$@";;
  diagnose) diagnose_cmd "$@";;
  run|demo|serve|status|stop) process_cmd "$command_name" "$@";;
  help|-h|--help) usage;;
  *) printf 'zero: unknown command: %s\n' "$command_name" >&2; usage >&2; exit 2;;
esac
