#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="${LOOTCHEST_TEST_RUNNER:-/Users/floris/Projects/Codex/servers/run-test-server}"
STARTUP_TIMEOUT="${LOOTCHEST_SMOKE_STARTUP_TIMEOUT:-180}"
COMMAND_TIMEOUT="${LOOTCHEST_SMOKE_COMMAND_TIMEOUT:-45}"
DEFAULT_JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-25.0.4.jdk/Contents/Home"

if [[ -z "${JAVA_BIN:-}" ]]; then
  if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  else
    JAVA_BIN="$DEFAULT_JAVA_HOME/bin/java"
  fi
fi
[[ -x "$JAVA_BIN" ]] || {
  printf '[smoke] Java executable not found: %s\n' "$JAVA_BIN" >&2
  exit 2
}
export JAVA_BIN

JAVA_VERSION_OUTPUT="$("$JAVA_BIN" -version 2>&1)"
JAVA_VERSION_LINE="${JAVA_VERSION_OUTPUT%%$'\n'*}"
if [[ "$JAVA_VERSION_LINE" =~ version\ \"([0-9]+) ]]; then
  JAVA_MAJOR="${BASH_REMATCH[1]}"
else
  printf '[smoke] Could not determine Java major version from: %s\n' "$JAVA_VERSION_LINE" >&2
  exit 2
fi

usage() {
  printf 'Usage: %s <LootChest Paper 26.2 jar>\n' "$(basename "$0")" >&2
}

fail() {
  printf '[smoke] FAIL: %s\n' "$*" >&2
  printf '[smoke] Raw log: %s\n' "$RAW_LOG" >&2
  printf '[smoke] Clean log: %s\n' "$CLEAN_LOG" >&2
  exit 1
}

if [[ "$#" -ne 1 ]]; then
  usage
  exit 2
fi

JAR_INPUT="$1"
if [[ "$JAR_INPUT" != /* ]]; then
  JAR_INPUT="$ROOT/$JAR_INPUT"
fi
[[ -f "$JAR_INPUT" ]] || {
  printf '[smoke] Jar not found: %s\n' "$JAR_INPUT" >&2
  exit 2
}
JAR="$(cd "$(dirname "$JAR_INPUT")" && pwd)/$(basename "$JAR_INPUT")"
[[ -x "$RUNNER" ]] || {
  printf '[smoke] Test runner is not executable: %s\n' "$RUNNER" >&2
  exit 2
}

STAMP="$(date '+%Y%m%d-%H%M%S')"
RUN_DIR="$ROOT/target/smoke-paper-26.2/$STAMP-java$JAVA_MAJOR"
RAW_LOG="$RUN_DIR/console.raw.log"
CLEAN_LOG="$RUN_DIR/console.log"
FIFO="$RUN_DIR/console.in"
METADATA_FILE="$RUN_DIR/lootchest-build.properties"
SERVER_PID=""
mkdir -p "$RUN_DIR"

if ! unzip -p "$JAR" lootchest-build.properties > "$METADATA_FILE"; then
  printf '[smoke] Embedded lootchest-build.properties is missing from %s\n' "$JAR" >&2
  exit 2
fi

metadata_value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$METADATA_FILE"
}

PAPER_TARGET="$(metadata_value paper.target)"
PAPER_BUILD="$(metadata_value paper.build)"
PAPER_CHANNEL="$(metadata_value paper.channel)"
PAPER_API="$(metadata_value paper.api)"
JAVA_TARGET="$(metadata_value java.target)"
ARTIFACT_NAME="$(metadata_value artifact.name)"

for required_value in PAPER_TARGET PAPER_BUILD PAPER_CHANNEL PAPER_API JAVA_TARGET ARTIFACT_NAME; do
  if [[ -z "${!required_value}" ]]; then
    printf '[smoke] Required release metadata is missing: %s\n' "$required_value" >&2
    exit 2
  fi
done
[[ "$JAVA_TARGET" == "25" ]] || {
  printf '[smoke] Expected Java 25 bytecode metadata, found Java %s\n' "$JAVA_TARGET" >&2
  exit 2
}
[[ "$(basename "$JAR")" == "$ARTIFACT_NAME" ]] || {
  printf '[smoke] Jar filename does not match embedded artifact name: %s != %s\n' \
    "$(basename "$JAR")" "$ARTIFACT_NAME" >&2
  exit 2
}

refresh_log() {
  if [[ -f "$RAW_LOG" ]]; then
    perl -pe 's/\e\[[0-9;?]*[ -\/]*[@-~]//g' "$RAW_LOG" > "$CLEAN_LOG"
  fi
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
    printf 'stop\n' >&3 2>/dev/null || true
    sleep 5
    if kill -0 "$SERVER_PID" >/dev/null 2>&1; then
      kill "$SERVER_PID" >/dev/null 2>&1 || true
    fi
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
  exec 3>&- || true
  rm -f "$FIFO"
  refresh_log
  exit "$status"
}
trap cleanup EXIT INT TERM
mkfifo "$FIFO"

wait_for_log() {
  local text="$1"
  local description="$2"
  local timeout="$3"
  local elapsed=0

  while (( elapsed < timeout )); do
    refresh_log
    if grep -Fq "$text" "$CLEAN_LOG" 2>/dev/null; then
      printf '[smoke] OK: %s\n' "$description"
      return 0
    fi
    if ! kill -0 "$SERVER_PID" >/dev/null 2>&1; then
      wait "$SERVER_PID" >/dev/null 2>&1 || true
      SERVER_PID=""
      fail "server exited while waiting for $description"
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done

  fail "timed out after ${timeout}s waiting for $description"
}

send_and_wait() {
  local command="$1"
  local response="$2"
  local description="$3"

  printf '[smoke] Command: %s\n' "$command"
  printf '%s\n' "$command" >&3
  wait_for_log "$response" "$description" "$COMMAND_TIMEOUT"
}

printf '[smoke] Jar: %s\n' "$JAR"
printf '[smoke] Java runtime: %s\n' "$JAVA_VERSION_LINE"
printf '[smoke] Embedded target: Paper %s build %s %s (API %s), Java %s bytecode\n' \
  "$PAPER_TARGET" "$PAPER_BUILD" "$PAPER_CHANNEL" "$PAPER_API" "$JAVA_TARGET"
printf '[smoke] Starting isolated Paper %s instance...\n' "$PAPER_TARGET"
"$RUNNER" \
  --paper "$PAPER_TARGET" \
  --project "lootchest-smoke-$STAMP-java$JAVA_MAJOR" \
  --project-dir "$ROOT" \
  --plugin "$JAR" \
  --require-plugin \
  --foreground \
  < "$FIFO" > "$RAW_LOG" 2>&1 &
SERVER_PID=$!
exec 3> "$FIFO"

wait_for_log "Running Java $JAVA_MAJOR " "Paper used Java $JAVA_MAJOR" "$STARTUP_TIMEOUT"
wait_for_log \
  "This server is running Paper version $PAPER_TARGET-$PAPER_BUILD-" \
  "Paper build $PAPER_BUILD started" \
  "$STARTUP_TIMEOUT"
wait_for_log "Implementing API version $PAPER_API" "Paper API $PAPER_API started" "$STARTUP_TIMEOUT"
wait_for_log "[LootChest] Plugin loaded" "LootChest enabled" "$STARTUP_TIMEOUT"
wait_for_log "$ARTIFACT_NAME" "LootChest reported the release artifact" "$STARTUP_TIMEOUT"
wait_for_log \
  "Paper $PAPER_TARGET build $PAPER_BUILD $PAPER_CHANNEL" \
  "LootChest reported the Paper release" \
  "$STARTUP_TIMEOUT"
send_and_wait "lc info" "Targets Paper $PAPER_TARGET" "/lc info reported the Paper target"
wait_for_log \
  "Paper release $PAPER_TARGET build $PAPER_BUILD $PAPER_CHANNEL" \
  "/lc info reported the Paper build and channel" \
  "$COMMAND_TIMEOUT"
send_and_wait "lc help" "Lootbox commands" "/lc help responded"
send_and_wait "lc list" "LootChests:" "/lc list responded"
send_and_wait \
  "lc reload" \
  "Configuration, locale, chest data, and LootChests were reloaded." \
  "/lc reload completed"
send_and_wait "lc despawnall" "All LootChests were despawned." "/lc despawnall completed"
send_and_wait "lc respawnall" "All LootChests were respawned." "/lc respawnall completed"

printf '[smoke] Command: stop\n'
printf 'stop\n' >&3
wait_for_log "[LootChest] Disabling LootChest" "LootChest began clean shutdown" "$COMMAND_TIMEOUT"
exec 3>&-

if ! wait "$SERVER_PID"; then
  SERVER_PID=""
  fail "Paper exited with a non-zero status"
fi
SERVER_PID=""
refresh_log

ERROR_PATTERN='Error occurred while (enabling|disabling) LootChest|NoClassDefFoundError|NoSuchMethodError|ClassNotFoundException|UnsupportedClassVersionError|CommandException|PluginClassLoader.*LootChest|zip file closed|\[ERROR\].*(LootChest|lootchest)|Exception.*fr\.black_eyes|fr\.black_eyes.*Exception'
if grep -Eiq "$ERROR_PATTERN" "$CLEAN_LOG"; then
  grep -Ein "$ERROR_PATTERN" "$CLEAN_LOG" >&2 || true
  fail "a LootChest compatibility error was found in the server log"
fi

PORT="$(sed -n 's/.*Paper 26\.2 ready on 127\.0\.0\.1:\([0-9][0-9]*\).*/\1/p' "$CLEAN_LOG" | tail -n 1)"
[[ -n "$PORT" ]] || fail "could not determine the temporary Paper port"

for _ in {1..10}; do
  if ! lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  fail "Paper stopped but port $PORT is still listening"
fi

INSTANCE_DIR="$(sed -n 's/.*starting foreground server in //p' "$CLEAN_LOG" | tail -n 1)"
printf '[smoke] PASS: Paper %s compatibility smoke test completed.\n' "$PAPER_TARGET"
printf '[smoke] Runtime: Java %s (%s)\n' "$JAVA_MAJOR" "$JAVA_VERSION_LINE"
printf '[smoke] Instance: %s\n' "${INSTANCE_DIR:-unknown}"
printf '[smoke] Port %s is free.\n' "$PORT"
printf '[smoke] Raw log: %s\n' "$RAW_LOG"
printf '[smoke] Clean log: %s\n' "$CLEAN_LOG"
