#!/usr/bin/env bash
# 阶段 3 Agent 冒烟：真实 HITL 下单 + 天气 + POI 搜索（每场景独立对话）。
#
#   1. HITL 下单 "帮我叫个车，从人民广场到浦东机场"
#      → OrderAgent 收集参数 → confirmOrder → confirm 事件（HITL 暂停）
#      → resume("确认下单") → createOrder → 助手正文含订单号
#   2. 天气 "我在经度121.47,纬度31.23，现在天气如何"
#      → DAILY 路由 → getWeatherNow → 正文含温度/天气现象关键词
#   3. POI 搜索 "搜一下浦东机场"
#      → DAILY 路由 → searchPOIs → 正文含地点候选关键词
#
# 依赖：DASHSCOPE_API_KEY（意图分类）+ DEEPSEEK_API_KEY（Agent 生成）缺一不可，
# 缺失时打印明确提示并跳过（exit 0），照 smoke-agent-phase2.sh 的处理风格。
# 天气与 POI 场景额外依赖 QWEATHER_KEY/QWEATHER_TOKEN（和风天气），缺失时该场景单独 SKIP。
# 密钥优先取环境变量，缺失时自动从 gitignored 的 secrets/local/*.env 加载
# （dashscope.env / deepseek.env / qweather.env，照 local-dev.ps1 的加载约定）。
# 场景 1 依赖本地服务栈（order-service 计价需可用；无 AMAP_KEY 时估算会失败，场景如实失败）。
#
# 可覆盖环境变量：
#   AGENT_SMOKE_TOKEN                   已有 USER Access Token（提供后跳过注册流程）
#   AGENT_SMOKE_PH3_HITL_KEYWORDS       场景 1 首轮流助手正文关键词（默认 确认,摘要,下单）
#   AGENT_SMOKE_PH3_RESUME_KEYWORDS     场景 1 resume 流助手正文关键词（默认 订单号,成功,下单）
#   AGENT_SMOKE_PH3_WEATHER_TOOLS       场景 2 必须调用的工具（默认 getWeatherNow）
#   AGENT_SMOKE_PH3_WEATHER_KEYWORDS    场景 2 正文关键词（默认 度,℃,温度,气温,晴,雨,风,湿度）
#   AGENT_SMOKE_PH3_POI_TOOLS           场景 3 必须调用的工具（默认 searchPOIs）
#   AGENT_SMOKE_PH3_POI_KEYWORDS        场景 3 正文关键词（默认 浦东机场,地址,经度,纬度,坐标,候选）
#   MAILPIT_UI_PORT                     Mailpit UI 端口（注册验证码抓取，默认 8025）
# 用法：
#   bash scripts/smoke-agent-phase3.sh
# 离线自测（不依赖服务与密钥，验证 SSE 契约断言逻辑）：
#   bash scripts/smoke-agent-phase3.sh --self-test
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"
PYTHON_BIN="${PYTHON_BIN:-python}"
MAILPIT_UI_PORT="${MAILPIT_UI_PORT:-8025}"
CURL_CONNECT_TIMEOUT_SECONDS="${AGENT_SMOKE_CONNECT_TIMEOUT_SECONDS:-5}"
CURL_HTTP_TIMEOUT_SECONDS="${AGENT_SMOKE_HTTP_TIMEOUT_SECONDS:-15}"
CURL_SSE_TIMEOUT_SECONDS="${AGENT_SMOKE_SSE_TIMEOUT_SECONDS:-70}"
REG_PASSWORD="${AGENT_SMOKE_PH3_PASSWORD:-SmokePass123!}"
# 各场景助手正文关键词（OR 语义）：见文件头注释
HITL_KEYWORDS="${AGENT_SMOKE_PH3_HITL_KEYWORDS:-确认,摘要,下单}"
RESUME_KEYWORDS="${AGENT_SMOKE_PH3_RESUME_KEYWORDS:-订单号,成功,下单}"
WEATHER_TOOLS="${AGENT_SMOKE_PH3_WEATHER_TOOLS:-getWeatherNow}"
WEATHER_KEYWORDS="${AGENT_SMOKE_PH3_WEATHER_KEYWORDS:-度,℃,温度,气温,晴,雨,风,湿度}"
POI_TOOLS="${AGENT_SMOKE_PH3_POI_TOOLS:-searchPOIs}"
POI_KEYWORDS="${AGENT_SMOKE_PH3_POI_KEYWORDS:-浦东机场,地址,经度,纬度,坐标,候选}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SSE_ASSERT="$SCRIPT_DIR/tests/sse_assert.py"
TMP_DIR=''
ACCESS_TOKEN=''

log_info() {
  printf '[agent-phase3] %s\n' "$1"
}

fail() {
  printf '[agent-phase3] FAIL: %s\n' "$1" >&2
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

# 从 gitignored secrets/local/*.env 加载缺失密钥（已有环境变量优先，照 local-dev.ps1 约定）
load_secrets() {
  local secrets_dir="${SECRETS_DIR:-}"
  [[ -n "$secrets_dir" ]] || secrets_dir="$(cd "$SCRIPT_DIR/.." && pwd)/secrets/local"
  for secrets_file in dashscope.env deepseek.env qweather.env; do
    local secrets_path="$secrets_dir/$secrets_file"
    [[ -f "$secrets_path" ]] || continue
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line//$'\r'/}"
      [[ "$line" == \#* || -z "$line" ]] && continue
      local name="${line%%=*}"
      local value="${line#*=}"
      if [[ -n "$name" && -z "${!name:-}" ]]; then
        export "$name=$value"
      fi
    done < "$secrets_path"
  done
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --self-test)
      exec bash "$SCRIPT_DIR/tests/smoke-agent-phase3-self-test.sh"
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
  shift
done

command -v curl >/dev/null 2>&1 || fail 'curl is required'
command -v "$PYTHON_BIN" >/dev/null 2>&1 || fail "$PYTHON_BIN is required"
[[ -f "$SSE_ASSERT" ]] || fail "SSE 断言助手缺失: $SSE_ASSERT"
load_secrets

# 密钥门禁：分类与 Agent 生成两类 key 缺一不可；缺失时明确提示并跳过（照 phase-2 风格）
MISSING_KEYS=()
[[ -n "${DASHSCOPE_API_KEY:-}" ]] || MISSING_KEYS+=('DASHSCOPE_API_KEY')
[[ -n "${DEEPSEEK_API_KEY:-}" ]] || MISSING_KEYS+=('DEEPSEEK_API_KEY')
if [[ ${#MISSING_KEYS[@]} -gt 0 ]]; then
  log_info "SKIP: 缺少模型密钥（${MISSING_KEYS[*]}）；分类与 Agent 生成均需真实模型，三个场景未执行"
  log_info "SKIP: 设置 export DASHSCOPE_API_KEY=... DEEPSEEK_API_KEY=... 或放入 secrets/local/*.env 后重跑"
  exit 0
fi
if [[ -n "${QWEATHER_KEY:-}" && -n "${QWEATHER_TOKEN:-}" ]]; then
  QWEATHER_READY=true
else
  QWEATHER_READY=false
  log_info 'WARN: 缺少 QWEATHER_KEY/QWEATHER_TOKEN；天气与 POI 场景将 SKIP（HITL 下单不受影响）'
fi

TMP_DIR="$(mktemp -d)"
trap cleanup EXIT

# ---------- 1. 用户身份：已有 Token 或注册新用户 ----------
if [[ -n "${AGENT_SMOKE_TOKEN:-}" ]]; then
  ACCESS_TOKEN="$AGENT_SMOKE_TOKEN"
  log_info 'using provided AGENT_SMOKE_TOKEN (registration skipped)'
else
  USER_EMAIL="agent-p3-$(date +%s)-${RANDOM:-0}@test.local"
  USER_NAME="agentp3$(date +%s)${RANDOM:-0}"
  log_info "registering a fresh user ($USER_EMAIL)"
  curl --silent \
    --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$CURL_HTTP_TIMEOUT_SECONDS" \
    --request POST "$BASE_URL/api/auth/email-code" \
    --header 'Content-Type: application/json' \
    --data-binary "{\"email\":\"$USER_EMAIL\",\"scene\":\"REGISTER\"}" \
    > /dev/null || fail 'email-code request failed'
  EMAIL_CODE=''
  for _ in $(seq 1 10); do
    EMAIL_CODE="$("$PYTHON_BIN" - "$USER_EMAIL" "$MAILPIT_UI_PORT" <<'PY' || true
import json
import re
import sys
import urllib.request

email, port = sys.argv[1], sys.argv[2]
base = f"http://127.0.0.1:{port}"
try:
    envelope = json.load(urllib.request.urlopen(base + "/api/v1/messages", timeout=5))
except Exception:
    sys.exit(0)
messages = envelope.get("messages", []) if isinstance(envelope, dict) else envelope
for item in messages:
    tos = item.get("To") or []
    if not any(email in str(entry.get("Address", "")) for entry in tos):
        continue
    detail = json.load(urllib.request.urlopen(base + "/api/v1/message/" + item.get("ID", ""), timeout=5))
    body = detail.get("Text", "") or detail.get("HTML", "")
    match = re.search(r"(\d{6})", body)
    if match:
        print(match.group(1))
        sys.exit(0)
sys.exit(0)
PY
)"
    if [[ -n "$EMAIL_CODE" ]]; then
      break
    fi
    sleep 2
  done
  [[ -n "$EMAIL_CODE" ]] || fail 'could not retrieve the register code from Mailpit'
  REGISTER_RESPONSE="$TMP_DIR/register.json"
  curl --silent \
    --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$CURL_HTTP_TIMEOUT_SECONDS" \
    --request POST "$BASE_URL/api/auth/register" \
    --header 'Content-Type: application/json' \
    --data-binary "{\"email\":\"$USER_EMAIL\",\"code\":\"$EMAIL_CODE\",\"password\":\"$REG_PASSWORD\",\"username\":\"$USER_NAME\",\"role\":\"USER\"}" \
    --output "$REGISTER_RESPONSE" || fail 'register request failed'
  ACCESS_TOKEN="$("$PYTHON_BIN" - "$REGISTER_RESPONSE" <<'PY' || true
import json
import sys
with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source).get("accessToken", ""))
PY
)"
  [[ -n "$ACCESS_TOKEN" ]] || fail 'register response does not contain accessToken'
  log_info 'user registered (token length hidden)'
fi

# ---------- 2. 三场景执行 ----------

create_conversation() {
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
  "$PYTHON_BIN" - "$TMP_DIR/create.json" <<'PY' || fail 'create response does not contain conversationId'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source).get("conversationId", ""))
PY
}

send_message() {
  local conversation_id="$1"
  local content="$2"
  local client_message_id
  client_message_id="$("$PYTHON_BIN" -c 'import uuid; print(uuid.uuid4())')"
  printf '{"clientMessageId":"%s","content":"%s"}\n' "$client_message_id" "$content" > "$TMP_DIR/message.json"
  if ! SSE_STATUS="$(curl --silent --no-buffer \
    --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$CURL_SSE_TIMEOUT_SECONDS" \
    --request POST "$BASE_URL/api/agent/conversations/$conversation_id/messages" \
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
}

resume_conversation() {
  local conversation_id="$1"
  local content="$2"
  local client_message_id
  client_message_id="$("$PYTHON_BIN" -c 'import uuid; print(uuid.uuid4())')"
  printf '{"clientMessageId":"%s","content":"%s"}\n' "$client_message_id" "$content" > "$TMP_DIR/resume.json"
  if ! RESUME_STATUS="$(curl --silent --no-buffer \
    --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$CURL_SSE_TIMEOUT_SECONDS" \
    --request POST "$BASE_URL/api/agent/conversations/$conversation_id/resume" \
    --header "Authorization: Bearer $ACCESS_TOKEN" \
    --header 'Content-Type: application/json' \
    --header 'Accept: text/event-stream' \
    --data-binary "@$TMP_DIR/resume.json" \
    --dump-header "$TMP_DIR/resume.headers" \
    --output "$TMP_DIR/resume.events" \
    --write-out '%{http_code}' 2> "$TMP_DIR/resume.curl-error")"; then
    fail 'resume SSE request failed before a terminal event was received'
  fi
  assert_http_status "$RESUME_STATUS" '200' 'resume conversation'
  assert_sse_content_type "$TMP_DIR/resume.headers"
}

# 场景 1：真实 HITL 下单（confirm 事件 → resume → 订单号）
log_info "scenario: 1-HITL-下单(帮我叫个车 + confirm + resume)"
HITL_CONVERSATION_ID="$(create_conversation)"
send_message "$HITL_CONVERSATION_ID" '帮我叫个车，从人民广场到浦东机场'
HITL_CONFIRMED=0
# 真实模型会按单体行为追问缺失参数（车型/预约等）；脚本模拟用户逐轮补参，直到 confirm 出现。
HITL_GUIDANCE=(
  '车型选快车，不预约不加急，从人民广场到浦东机场'
  '确认，就按这个行程继续下单'
  '继续下单'
)
for HITL_ROUND in 1 2 3 4; do
  if "$PYTHON_BIN" "$SSE_ASSERT" "$TMP_DIR/sse.events" \
      'completed' 'confirmOrder' '' '' "$HITL_KEYWORDS" '1' '0' 'confirm'; then
    HITL_CONFIRMED=1
    break
  fi
  if [[ "$HITL_ROUND" -le "${#HITL_GUIDANCE[@]}" ]]; then
    log_info "首轮未达 confirm（第 $HITL_ROUND 轮），发送补参引导：${HITL_GUIDANCE[$((HITL_ROUND-1))]}"
    send_message "$HITL_CONVERSATION_ID" "${HITL_GUIDANCE[$((HITL_ROUND-1))]}"
  else
    log_info "第 $HITL_ROUND 轮仍未达 confirm，停止引导"
    break
  fi
done
if [[ "$HITL_CONFIRMED" == "1" ]]; then
  RESULTS+=("PASS  1-HITL-首轮(confirm 事件 + confirmOrder)")
else
  RESULTS+=("FAIL  1-HITL-首轮(confirm 事件 + confirmOrder)")
fi
resume_conversation "$HITL_CONVERSATION_ID" '确认下单'
if "$PYTHON_BIN" "$SSE_ASSERT" "$TMP_DIR/resume.events" \
    'completed' 'createOrder' '' '' "$RESUME_KEYWORDS" '1' '0' ''; then
  RESULTS+=("PASS  1-HITL-resume(createOrder + 订单号)")
else
  RESULTS+=("FAIL  1-HITL-resume(createOrder + 订单号)")
fi
# 尽力而为：从 resume 流提取订单号并回查 order-service（非阻断，失败仅告警）
ORDER_ID="$(grep -oE '[0-9]{15,20}' "$TMP_DIR/resume.events" 2>/dev/null | head -n 1 || true)"
if [[ -n "$ORDER_ID" ]]; then
  VERIFY_STATUS="$(curl --silent \
    --connect-timeout "$CURL_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$CURL_HTTP_TIMEOUT_SECONDS" \
    --request GET "$BASE_URL/api/orders/$ORDER_ID" \
    --header "Authorization: Bearer $ACCESS_TOKEN" \
    --output /dev/null \
    --write-out '%{http_code}' 2>/dev/null || true)"
  if [[ "$VERIFY_STATUS" == "200" ]]; then
    log_info "INFO: order-service 确认订单存在（订单号 $ORDER_ID）"
  else
    log_info "WARN: order-service 订单回查未返回 200（$VERIFY_STATUS）；订单仍已由 createOrder 创建"
  fi
else
  log_info 'WARN: resume 流未提取到订单号，跳过 order-service 回查'
fi

# 场景 2：天气（QWEATHER 缺失时 SKIP）
if [[ "$QWEATHER_READY" == true ]]; then
  log_info "scenario: 2-天气(经度121.47,纬度31.23 现在天气)"
  WEATHER_CONVERSATION_ID="$(create_conversation)"
  send_message "$WEATHER_CONVERSATION_ID" '我在经度121.47,纬度31.23，现在天气如何'
  if "$PYTHON_BIN" "$SSE_ASSERT" "$TMP_DIR/sse.events" \
      'completed' "$WEATHER_TOOLS" '' '' "$WEATHER_KEYWORDS" '1' '0' ''; then
    RESULTS+=("PASS  2-天气(经度121.47,纬度31.23 现在天气)")
  else
    RESULTS+=("FAIL  2-天气(经度121.47,纬度31.23 现在天气)")
  fi
else
  log_info 'SKIP  2-天气（缺少 QWEATHER_KEY/QWEATHER_TOKEN）'
  RESULTS+=("SKIP  2-天气（缺少 QWEATHER_KEY/QWEATHER_TOKEN）")
fi

# 场景 3：POI 搜索（QWEATHER 缺失时 SKIP）
if [[ "$QWEATHER_READY" == true ]]; then
  log_info "scenario: 3-POI(搜一下浦东机场)"
  POI_CONVERSATION_ID="$(create_conversation)"
  send_message "$POI_CONVERSATION_ID" '搜一下浦东机场'
  if "$PYTHON_BIN" "$SSE_ASSERT" "$TMP_DIR/sse.events" \
      'completed' "$POI_TOOLS" '' '' "$POI_KEYWORDS" '1' '0' ''; then
    RESULTS+=("PASS  3-POI(搜一下浦东机场)")
  else
    RESULTS+=("FAIL  3-POI(搜一下浦东机场)")
  fi
else
  log_info 'SKIP  3-POI（缺少 QWEATHER_KEY/QWEATHER_TOKEN）'
  RESULTS+=("SKIP  3-POI（缺少 QWEATHER_KEY/QWEATHER_TOKEN）")
fi

# ---------- 3. 汇总 ----------
FAILED_COUNT=0
for result in "${RESULTS[@]}"; do
  log_info "$result"
  case "$result" in
    FAIL*)
      FAILED_COUNT=$((FAILED_COUNT + 1))
      ;;
  esac
done
if [[ "$FAILED_COUNT" -gt 0 ]]; then
  fail "$FAILED_COUNT 个场景失败（详见上方逐场景结果）"
fi
log_info 'SMOKE PASS'
