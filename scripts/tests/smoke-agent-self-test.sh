#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SMOKE_SCRIPT="$(cd -- "$SCRIPT_DIR/.." && pwd)/smoke-agent.sh"
TOKEN_SENTINEL='self-test-sensitive-token'
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf -- "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$TMP_DIR/shim"
cat > "$TMP_DIR/shim/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail

scenario="${FAKE_CURL_SCENARIO:?}"
state_dir="${FAKE_CURL_STATE_DIR:?}"
output_file=''
headers_file=''
write_out=''
url=''
has_connect_timeout=false
has_total_timeout=false
has_expected_token=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --connect-timeout)
      has_connect_timeout=true
      shift 2
      ;;
    --max-time)
      has_total_timeout=true
      shift 2
      ;;
    --output)
      output_file="$2"
      shift 2
      ;;
    --dump-header)
      headers_file="$2"
      shift 2
      ;;
    --write-out)
      write_out="$2"
      shift 2
      ;;
    --header)
      if [[ "$2" == "Authorization: Bearer ${AGENT_SMOKE_TOKEN:?}" ]]; then
        has_expected_token=true
      fi
      shift 2
      ;;
    --request|--data-binary)
      shift 2
      ;;
    --silent|--no-buffer)
      shift
      ;;
    http://*|https://*)
      url="$1"
      shift
      ;;
    *)
      shift
      ;;
  esac
done

[[ "$has_connect_timeout" == true ]] || exit 91
[[ "$has_total_timeout" == true ]] || exit 92
[[ -n "$output_file" ]] || exit 93
[[ "$write_out" == '%{http_code}' ]] || exit 94

call_file="$state_dir/calls"
call_number=1
if [[ -f "$call_file" ]]; then
  call_number=$(( $(<"$call_file") + 1 ))
fi
printf '%s' "$call_number" > "$call_file"

if [[ "$has_expected_token" == true ]]; then
  : > "$state_dir/token-seen"
fi

case "$call_number" in
  1)
    [[ "$url" == */api/agent/conversations ]] || exit 95
    printf '{"conversationId":"conversation-self-test"}\n' > "$output_file"
    if [[ "$scenario" == bad-create-status ]]; then
      printf '500'
    else
      printf '201'
    fi
    ;;
  2)
    [[ "$url" == */api/agent/conversations ]] || exit 96
    printf '{"code":"UNAUTHORIZED"}\n' > "$output_file"
    if [[ "$scenario" == bad-invalid-status ]]; then
      printf '403'
    else
      printf '401'
    fi
    ;;
  3)
    [[ "$url" == */api/agent/conversations/conversation-self-test/messages ]] || exit 97
    [[ -n "$headers_file" ]] || exit 98
    printf 'HTTP/1.1 200 OK\r\nContent-Type: text/event-stream;charset=UTF-8\r\n\r\n' > "$headers_file"
    case "$scenario" in
      success)
        printf 'event: run.started\ndata: {"runId":"r-1"}\n\nevent: message.delta\ndata: {"delta":"ok"}\n\nevent: message.completed\ndata: {"messageId":"m-1"}\n\nevent: run.completed\ndata: {"runId":"r-1"}\n\n' > "$output_file"
        ;;
      rag-failure)
        printf 'event: run.started\ndata: {"runId":"r-2"}\n\nevent: run.failed\ndata: {"runId":"r-2","errorCode":"RAG_UNAVAILABLE"}\n\n' > "$output_file"
        ;;
      missing-event)
        printf 'event: run.started\ndata: {"runId":"r-3"}\n\n' > "$output_file"
        ;;
      bad-rag-code)
        printf 'event: run.started\ndata: {"runId":"r-4"}\n\nevent: run.failed\ndata: {"runId":"r-4","errorCode":"INTERNAL_ERROR"}\n\n' > "$output_file"
        ;;
      out-of-order)
        printf 'event: message.completed\ndata: {"messageId":"m-5"}\n\nevent: run.started\ndata: {"runId":"r-5"}\n\nevent: run.completed\ndata: {"runId":"r-5"}\n\n' > "$output_file"
        ;;
      duplicate-terminal)
        printf 'event: run.started\ndata: {"runId":"r-6"}\n\nevent: message.completed\ndata: {"messageId":"m-6"}\n\nevent: run.completed\ndata: {"runId":"r-6"}\n\nevent: run.completed\ndata: {"runId":"r-6"}\n\n' > "$output_file"
        ;;
      terminal-not-last)
        printf 'event: run.started\ndata: {"runId":"r-7"}\n\nevent: run.completed\ndata: {"runId":"r-7"}\n\nevent: message.completed\ndata: {"messageId":"m-7"}\n\n' > "$output_file"
        ;;
      success-with-failed)
        printf 'event: run.started\ndata: {"runId":"r-8"}\n\nevent: message.completed\ndata: {"messageId":"m-8"}\n\nevent: run.failed\ndata: {"runId":"r-8","errorCode":"RAG_UNAVAILABLE"}\n\n' > "$output_file"
        ;;
      network-error)
        exit 7
        ;;
      *)
        exit 99
        ;;
    esac
    printf '200'
    ;;
  *)
    exit 100
    ;;
esac
FAKE_CURL
chmod +x "$TMP_DIR/shim/curl"

run_case() {
  local scenario="$1"
  local expected_exit="$2"
  local expectation="${3:-success}"
  local case_dir="$TMP_DIR/$scenario"
  local output_file="$case_dir/output.log"
  local status=0
  mkdir -p "$case_dir/state"

  set +e
  PATH="$TMP_DIR/shim:$PATH" \
    AGENT_SMOKE_TOKEN="$TOKEN_SENTINEL" \
    DEEPSEEK_API_KEY='self-test-key' \
    FAKE_CURL_SCENARIO="$scenario" \
    FAKE_CURL_STATE_DIR="$case_dir/state" \
    bash "$SMOKE_SCRIPT" $([[ "$expectation" == rag-failure ]] && printf '%s' '--expect-rag-failure') \
    > "$output_file" 2>&1
  status=$?
  set -e

  if grep -Fq "$TOKEN_SENTINEL" "$output_file"; then
    printf '[agent-smoke-self-test] FAIL: %s leaked token sentinel\n' "$scenario" >&2
    return 1
  fi
  if grep -Eiq 'authorization:|invalid-agent-smoke-token|^HTTP/|^content-type:' "$output_file"; then
    printf '[agent-smoke-self-test] FAIL: %s leaked a request or response header\n' "$scenario" >&2
    return 1
  fi
  [[ -f "$case_dir/state/token-seen" ]] || {
    printf '[agent-smoke-self-test] FAIL: %s did not pass the token to fake curl\n' "$scenario" >&2
    return 1
  }
  if [[ "$expected_exit" == zero && "$status" -ne 0 ]]; then
    printf '[agent-smoke-self-test] FAIL: %s expected success but exited %s\n' "$scenario" "$status" >&2
    return 1
  fi
  if [[ "$expected_exit" == nonzero && "$status" -eq 0 ]]; then
    printf '[agent-smoke-self-test] FAIL: %s unexpectedly succeeded\n' "$scenario" >&2
    return 1
  fi
  if [[ "$scenario" == network-error ]] \
      && ! grep -Fq 'FAIL: message SSE request failed before a terminal event was received' "$output_file"; then
    printf '[agent-smoke-self-test] FAIL: network error did not use the stable safe message\n' >&2
    return 1
  fi
}

run_case success zero
run_case rag-failure zero rag-failure
run_case bad-create-status nonzero
run_case bad-invalid-status nonzero
run_case missing-event nonzero
run_case bad-rag-code nonzero rag-failure
run_case out-of-order nonzero
run_case duplicate-terminal nonzero
run_case terminal-not-last nonzero
run_case success-with-failed nonzero
run_case network-error nonzero

printf '[agent-smoke] SELF-TEST PASS\n'
