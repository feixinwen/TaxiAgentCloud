#!/usr/bin/env bash
# 阶段 3 冒烟脚本离线自测：用预置 SSE 事件流验证 sse_assert.py 对 HITL 流的契约断言
# （不依赖服务与密钥）。覆盖：HITL 首轮通过流（confirm 事件 + confirmOrder 工具 + 摘要正文）、
# resume 通过流（createOrder + 订单号正文）、以及反例（confirm 缺失、confirmOrder 未调用、
# 摘要正文未命中、createOrder 未调用、订单号正文未命中）。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SSE_ASSERT="$SCRIPT_DIR/sse_assert.py"
PYTHON_BIN="${PYTHON_BIN:-python}"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf -- "$TMP_DIR"
}
trap cleanup EXIT

run_case() {
  local name="$1"            # 用例名
  local expected_status="$2" # zero | nonzero
  local events_file="$3"
  shift 3
  local output_file="$TMP_DIR/$name.log"
  local status=0
  set +e
  "$PYTHON_BIN" "$SSE_ASSERT" "$events_file" "$@" > "$output_file" 2>&1
  status=$?
  set -e
  if [[ "$expected_status" == zero && "$status" -ne 0 ]]; then
    printf '[phase3-self-test] FAIL: %s 预期通过但退出码 %s\n' "$name" "$status" >&2
    cat "$output_file" >&2
    return 1
  fi
  if [[ "$expected_status" == nonzero && "$status" -eq 0 ]]; then
    printf '[phase3-self-test] FAIL: %s 预期失败但意外通过\n' "$name" >&2
    return 1
  fi
  printf '[phase3-self-test] ok: %s\n' "$name"
}

# HITL 首轮通过流：填槽工具 + confirmOrder + confirm 事件 + 摘要正文 + message.completed
cat > "$TMP_DIR/hitl-first.events" <<'EOF'
event: run.started
data: {"runId":"r-1","conversationId":"c-1"}

event: tool.started
data: {"runId":"r-1","toolCallId":"t-1","toolName":"saveOrderParam"}

event: tool.completed
data: {"runId":"r-1","toolCallId":"t-1","toolName":"saveOrderParam","success":true,"durationMs":5}

event: tool.started
data: {"runId":"r-1","toolCallId":"t-2","toolName":"confirmOrder"}

event: tool.completed
data: {"runId":"r-1","toolCallId":"t-2","toolName":"confirmOrder","success":true,"durationMs":8}

event: confirm
data: {"runId":"r-1","content":"{\"startAddress\":\"人民广场\",\"endAddress\":\"浦东机场\",\"estPrice\":150}"}

event: message.delta
data: {"runId":"r-1","content":"已为您生成订单摘要，请确认下单"}

event: message.completed
data: {"runId":"r-1","messageId":201}

event: run.completed
data: {"runId":"r-1"}
EOF

# HITL resume 通过流：createOrder + 订单号正文
cat > "$TMP_DIR/hitl-resume.events" <<'EOF'
event: run.started
data: {"runId":"r-2","conversationId":"c-1"}

event: tool.started
data: {"runId":"r-2","toolCallId":"t-3","toolName":"createOrder"}

event: tool.completed
data: {"runId":"r-2","toolCallId":"t-3","toolName":"createOrder","success":true,"durationMs":120}

event: message.delta
data: {"runId":"r-2","content":"下单成功，您的订单号为 2088082600012345678。"}

event: message.completed
data: {"runId":"r-2","messageId":202}

event: run.completed
data: {"runId":"r-2"}
EOF

# 反例：首轮流缺少 confirm 事件
cat > "$TMP_DIR/confirm-missing.events" <<'EOF'
event: run.started
data: {"runId":"r-3","conversationId":"c-2"}

event: tool.started
data: {"runId":"r-3","toolCallId":"t-4","toolName":"saveOrderParam"}

event: tool.completed
data: {"runId":"r-3","toolCallId":"t-4","toolName":"saveOrderParam","success":true,"durationMs":5}

event: message.delta
data: {"runId":"r-3","content":"请先补充起点信息"}

event: run.completed
data: {"runId":"r-3"}
EOF

# 反例：confirmOrder 未被调用（confirm 事件断言前置条件）
cat > "$TMP_DIR/confirm-tool-missing.events" <<'EOF'
event: run.started
data: {"runId":"r-4","conversationId":"c-3"}

event: message.delta
data: {"runId":"r-4","content":"请问您的出发地和目的地是？"}

event: run.completed
data: {"runId":"r-4"}
EOF

# 反例：resume 流未调用 createOrder
cat > "$TMP_DIR/create-missing.events" <<'EOF'
event: run.started
data: {"runId":"r-5","conversationId":"c-4"}

event: message.delta
data: {"runId":"r-5","content":"已为您重新生成摘要，请确认"}

event: message.completed
data: {"runId":"r-5","messageId":203}

event: run.completed
data: {"runId":"r-5"}
EOF

# 正例：HITL 首轮流（confirm 事件 + confirmOrder 工具 + 摘要关键词）
run_case hitl-first-pass zero "$TMP_DIR/hitl-first.events" \
  completed saveOrderParam,confirmOrder '' '' '确认,摘要' 1 0 confirm
# 正例：resume 流（createOrder 工具 + 订单号关键词）
run_case hitl-resume-pass zero "$TMP_DIR/hitl-resume.events" \
  completed createOrder '' '' '订单号,成功' 1 0 ''
# 反例：缺少 confirm 事件
run_case confirm-missing-fail nonzero "$TMP_DIR/confirm-missing.events" \
  completed saveOrderParam '' '' '' 1 0 confirm
# 反例：confirmOrder 未调用
run_case confirm-tool-missing-fail nonzero "$TMP_DIR/confirm-tool-missing.events" \
  completed confirmOrder '' '' '' 1 0 ''
# 反例：resume 流未调用 createOrder
run_case create-missing-fail nonzero "$TMP_DIR/create-missing.events" \
  completed createOrder '' '' '订单号' 1 0 ''

printf '[phase3-self-test] SELF-TEST PASS\n'
