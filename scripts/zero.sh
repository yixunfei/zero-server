#!/usr/bin/env bash
set -euo pipefail

# zeroServer cross-platform thin entrypoint. It delegates behavior to existing Java tools.
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_CMD="${MAVEN_CMD:-${ZERO_MAVEN_CMD:-}}"
if [[ -z "$MAVEN_CMD" && -x "$ROOT_DIR/mvnw" ]]; then MAVEN_CMD="$ROOT_DIR/mvnw"; fi
STATE_DIR="${ZERO_STATE_DIR:-${ROOT_DIR}/target/zero-entry}"
PID_FILE="${STATE_DIR}/server.pid"
META_FILE="${STATE_DIR}/server.meta"

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
  run --projectDir DIR           Run generated project and record a controlled PID
  stop                           Stop only the PID recorded by this entrypoint
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
  if is_windows_host; then
    (cd "$ROOT_DIR" && ZERO_MAVEN_CMD="mvn.cmd" "$(java_cmd)" "$@")
  else
    (cd "$ROOT_DIR" && ZERO_MAVEN_CMD="mvn" "$(java_cmd)" "$@")
  fi
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
    (cd "$ROOT_DIR" && "$(mvn_cmd)" -B -ntp -f "$project_pom" test "${args[@]-}")
  else
    (cd "$ROOT_DIR" && "$(mvn_cmd)" -B -ntp test "${args[@]-}")
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

run_cmd() {
  require_root
  local project_dir=""
  while (($#)); do
    case "$1" in
      --projectDir) [[ $# -ge 2 ]] || { printf 'zero: --projectDir requires a value\n' >&2; exit 2; }; project_dir="$2"; shift 2;;
      *) printf 'zero: unknown run option: %s\n' "$1" >&2; exit 2;;
    esac
  done
  [[ -n "$project_dir" && -f "$project_dir/pom.xml" ]] || { printf 'zero: run requires a generated project with --projectDir DIR\n' >&2; exit 2; }
  mkdir -p "$STATE_DIR"
  if [[ -s "$PID_FILE" ]]; then
    local old_pid; old_pid="$(tr -d '[:space:]' < "$PID_FILE")"
    if [[ "$old_pid" =~ ^[0-9]+$ ]] && kill -0 "$old_pid" 2>/dev/null; then
      printf 'zero: a managed server is already running (pid=%s)\n' "$old_pid" >&2; exit 1
    fi
    rm -f "$PID_FILE" "$META_FILE"
  fi
  (cd "$project_dir" && exec "$(mvn_cmd)" -q "-Dzero.entry.projectDir=$project_dir" exec:java) >"$STATE_DIR/server.log" 2>&1 &
  local pid=$!
  printf '%s\n' "$pid" > "$PID_FILE"
  printf 'pid=%s\nprojectDir=%s\n' "$pid" "$(cd "$project_dir" && pwd)" > "$META_FILE"
  printf 'zero-run=started|pid=%s|projectDir=%s|log=%s\n' "$pid" "$project_dir" "$STATE_DIR/server.log"
}

stop_cmd() {
  mkdir -p "$STATE_DIR"
  if [[ ! -s "$PID_FILE" ]]; then printf 'zero-stop=idle|managed=false\n'; return 0; fi
  local pid; pid="$(tr -d '[:space:]' < "$PID_FILE")"
  [[ "$pid" =~ ^[0-9]+$ ]] || { rm -f "$PID_FILE" "$META_FILE"; printf 'zero-stop=cleaned|managed=false\n'; return 0; }
  if kill -0 "$pid" 2>/dev/null; then
    local project_dir=""
    if [[ -s "$META_FILE" ]]; then project_dir="$(grep '^projectDir=' "$META_FILE" | cut -d= -f2-)"; fi
    local command_line; command_line="$(ps -p "$pid" -o args= 2>/dev/null || true)"
    if [[ -z "$command_line" || ( "$command_line" != *mvn* && "$command_line" != *mvnw* && ( -z "$project_dir" || "$(readlink "/proc/$pid/cwd" 2>/dev/null || true)" != "$project_dir" ) ) ]]; then
      printf 'zero-stop=refused|reason=managed-pid-identity-mismatch|pid=%s\n' "$pid" >&2
      exit 1
    fi
    kill "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5; do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
    printf 'zero-stop=stopped|pid=%s\n' "$pid"
  else
    printf 'zero-stop=already-stopped|pid=%s\n' "$pid"
  fi
  rm -f "$PID_FILE" "$META_FILE"
}

command_name="${1:-help}"; [[ $# -gt 0 ]] && shift
case "$command_name" in
  doctor) doctor "$@";;
  init) init_cmd "$@";;
  generate) generate_cmd "$@";;
  test) test_cmd "$@";;
  diagnose) diagnose_cmd "$@";;
  run) run_cmd "$@";;
  stop) stop_cmd "$@";;
  help|-h|--help) usage;;
  *) printf 'zero: unknown command: %s\n' "$command_name" >&2; usage >&2; exit 2;;
esac
