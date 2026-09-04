#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"
PYTHON_BIN="${PYTHON_BIN:-python}"
EXPECT_RAG_FAILURE="${AGENT_SMOKE_EXPECT_RAG_FAILURE:-false}"
CURL_CONNECT_TIMEOUT_SECONDS="${AGENT_SMOKE_CONNECT_TIMEOUT_SECONDS:-5}"
CURL_HTTP_TIMEOUT_SECONDS="${AGENT_SMOKE_HTTP_TIMEOUT_SECONDS:-15}"
CURL_SSE_TIMEOUT_SECONDS="${AGENT_SMOKE_SSE_TIMEOUT_SECONDS:-70}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TMP_DIR=''

log_info() {
  printf '[agent-smoke] %s\n' "$1"
}

fail() {
  printf '[agent-smoke] FAIL: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$TMP_DIR" && -d "$TMP_DIR" ]]; then
    rm -rf -- "$TMP_DIR"
  fi
}

assert_http_status() {
  local actual="$1"
  local expected="$2"
  local operation="$3"
  [[ "$actual" == "$expected" ]] || fail "$operation expected HTTP $expected but got $actual"
}

assert_sse_content_type() {
  local headers_file="$1"
  tr -d '\r' < "$headers_file" | grep -Eiq '^content-type:[[:space:]]*text/event-stream([[:space:]]*;|[[:space:]]*$)' \
    || fail 'message response is not text/event-stream'
}

assert_sse_contract() {
  local events_file="$1"
  local expected_terminal="$2"
  "$PYTHON_BIN" - "$events_file" "$expected_terminal" <<'PY' || return 1
import json
import sys

events_path, expected_terminal = sys.argv[1:]
with open(events_path, encoding="utf-8") as source:
    raw = source.read().replace("\r\n", "\n").replace("\r", "\n")

business_events = []
for block in raw.split("\n\n"):
    if not block.strip():
        continue
    event_name = None
    data_lines = []
    for line in block.splitlines():
        if line.startswith("event:"):
            event_name = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
    if event_name and event_name not in {"heartbeat", "ping"}:
        business_events.append((event_name, "\n".join(data_lines)))

if not business_events or business_events[0][0] != "run.started":
    raise SystemExit("first business event must be run.started")

terminal_indexes = [
    index for index, (name, _) in enumerate(business_events)
    if name in {"run.completed", "run.failed"}
]
if len(terminal_indexes) != 1:
    raise SystemExit("SSE stream must contain exactly one terminal event")
terminal_index = terminal_indexes[0]
terminal_name, terminal_data = business_events[terminal_index]
if terminal_index != len(business_events) - 1:
    raise SystemExit("terminal event must be the last business event")
if terminal_name != expected_terminal:
    raise SystemExit(f"expected {expected_terminal} but received {terminal_name}")

if expected_terminal == "run.completed":
    if not any(name == "message.completed" for name, _ in business_events[:terminal_index]):
        raise SystemExit("successful stream must contain message.completed before run.completed")
else:
    try:
        terminal_payload = json.loads(terminal_data)
    except json.JSONDecodeError as error:
        raise SystemExit("run.failed data must be valid JSON") from error
    if terminal_payload.get("errorCode") not in {"RAG_UNAVAILABLE", "RAG_REQUEST_REJECTED"}:
        raise SystemExit("run.failed must carry a stable RAG errorCode in the same SSE block")
PY
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --self-test)
      exec bash "$SCRIPT_DIR/tests/smoke-agent-self-test.sh"
      ;;
    --expect-rag-failure)
      EXPECT_RAG_FAILURE=true
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
  shift
done

command -v curl >/dev/null 2>&1 || fail 'curl is required'
command -v "$PYTHON_BIN" >/dev/null 2>&1 || fail "$PYTHON_BIN is required"

ACCESS_TOKEN="${AGENT_SMOKE_TOKEN:-}"
[[ -n "$ACCESS_TOKEN" ]] || fail 'AGENT_SMOKE_TOKEN is required and must contain a valid USER access token'

TMP_DIR="$(mktemp -d)"
trap cleanup EXIT

log_info 'creating a conversation through Gateway'
if ! CREATE_STATUS="$(curl --silent \
  --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
  --max-time "$CURL_HTTP_TIMEOUT_SECONDS" \
  --request POST "$BASE_URL/api/agent/conversations" \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --header 'Accept: application/json' \
  --output "$TMP_DIR/create.json" \
  --write-out '%{http_code}' 2> "$TMP_DIR/create.curl-error")"; then
  fail 'create conversation request failed'
fi
assert_http_status "$CREATE_STATUS" '201' 'create conversation'

CONVERSATION_ID="$("$PYTHON_BIN" - "$TMP_DIR/create.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    value = json.load(source).get("conversationId", "")
if not value:
    raise SystemExit(1)
print(value)
PY
)" || fail 'create response does not contain conversationId'
[[ -n "$CONVERSATION_ID" ]] || fail 'create response contains an empty conversationId'
log_info 'conversation creation returned HTTP 201'

log_info 'checking invalid token rejection'
if ! INVALID_STATUS="$(curl --silent \
  --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
  --max-time "$CURL_HTTP_TIMEOUT_SECONDS" \
  --request POST "$BASE_URL/api/agent/conversations" \
  --header 'Authorization: Bearer invalid-agent-smoke-token' \
  --header 'Accept: application/json' \
  --output "$TMP_DIR/invalid.json" \
  --write-out '%{http_code}' 2> "$TMP_DIR/invalid.curl-error")"; then
  fail 'invalid-token request failed'
fi
assert_http_status "$INVALID_STATUS" '401' 'invalid token'
log_info 'invalid token returned HTTP 401'

if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  log_info 'SKIP: DEEPSEEK_API_KEY is absent; real-model SSE success and RAG-fault paths were not executed'
  exit 0
fi

CLIENT_MESSAGE_ID="$("$PYTHON_BIN" - <<'PY'
import uuid
print(uuid.uuid4())
PY
)"
printf '{"clientMessageId":"%s","content":"取消订单会收费吗？"}\n' "$CLIENT_MESSAGE_ID" > "$TMP_DIR/message.json"

log_info 'sending a message and validating the SSE contract'
if ! SSE_STATUS="$(curl --silent --no-buffer \
  --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
  --max-time "$CURL_SSE_TIMEOUT_SECONDS" \
  --request POST "$BASE_URL/api/agent/conversations/$CONVERSATION_ID/messages" \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --header 'Content-Type: application/json' \
  --header 'Accept: text/event-stream' \
  --data-binary "@$TMP_DIR/message.json" \
  --dump-header "$TMP_DIR/sse.headers" \
  --output "$TMP_DIR/sse.events" \
  --write-out '%{http_code}' 2> "$TMP_DIR/sse.curl-error")"; then
  fail 'message SSE request failed before a terminal event was received'
fi
assert_http_status "$SSE_STATUS" '200' 'send message'
assert_sse_content_type "$TMP_DIR/sse.headers"

if [[ "$EXPECT_RAG_FAILURE" == true ]]; then
  assert_sse_contract "$TMP_DIR/sse.events" 'run.failed' \
    || fail 'RAG fault SSE contract validation failed'
  log_info 'RAG fault produced one terminal run.failed with a stable RAG errorCode'
else
  assert_sse_contract "$TMP_DIR/sse.events" 'run.completed' \
    || fail 'successful SSE contract validation failed'
  log_info 'SSE ordering and unique terminal run.completed are valid'
  log_info 'To verify RAG failure, make RAG unavailable and rerun with --expect-rag-failure'
fi

log_info 'SMOKE PASS'
