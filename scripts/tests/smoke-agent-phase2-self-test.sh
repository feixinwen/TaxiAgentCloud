#!/usr/bin/env bash
# 阶段 2 冒烟脚本离线自测：用预置 SSE 事件流验证 sse_assert.py 契约断言（不依赖服务与密钥）。
# 覆盖：各场景通过流、ORDER 粘滞 notify、DANGER 不落库流、order-failed 终态、以及反例
# （缺终态、终态不居尾、重复终态、非法错误码、违禁工具、正文关键词未命中、notify 缺失、首事件非 run.started）。
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
    printf '[phase2-self-test] FAIL: %s 预期通过但退出码 %s\n' "$name" "$status" >&2
    cat "$output_file" >&2
    return 1
  fi
  if [[ "$expected_status" == nonzero && "$status" -eq 0 ]]; then
    printf '[phase2-self-test] FAIL: %s 预期失败但意外通过\n' "$name" >&2
    return 1
  fi
  printf '[phase2-self-test] ok: %s\n' "$name"
}

# 场景 1 通过流：SUPPORT + searchKnowledgeBase + 知识库关键词 + message.completed
cat > "$TMP_DIR/support.events" <<'EOF'
event: run.started
data: {"runId":"r-1","conversationId":"c-1"}

event: tool.started
data: {"runId":"r-1","toolCallId":"t-1","toolName":"searchKnowledgeBase"}

event: tool.completed
data: {"runId":"r-1","toolCallId":"t-1","toolName":"searchKnowledgeBase","success":true,"durationMs":12}

event: message.delta
data: {"runId":"r-1","content":"电子发票请前往订单详情页申请"}

event: message.delta
data: {"runId":"r-1","content":"，24小时内发送至您的邮箱。"}

event: message.completed
data: {"runId":"r-1","messageId":101}

event: run.completed
data: {"runId":"r-1"}
EOF

# 场景 4 DANGER 通过流：固定话术、无工具、无 message.completed、run.completed 终结
cat > "$TMP_DIR/danger.events" <<'EOF'
event: run.started
data: {"runId":"r-2"}

event: message.delta
data: {"runId":"r-2","content":"您的命令不被支持，请换个内容继续吧。"}

event: run.completed
data: {"runId":"r-2"}
EOF

# ORDER 粘滞通过流：notify 话术 + OrderAgent 续跑
cat > "$TMP_DIR/sticky.events" <<'EOF'
event: run.started
data: {"runId":"r-3"}

event: notify
data: {"runId":"r-3","content":"当前处于下单流程，如果您想换个话题，请新建对话。"}

event: message.delta
data: {"runId":"r-3","content":"好的，我们继续处理您的订单。"}

event: message.completed
data: {"runId":"r-3","messageId":102}

event: run.completed
data: {"runId":"r-3"}
EOF

# estimatePrice 上游拒绝路径：run.failed + ORDER_UNAVAILABLE（completed-or-order-failed 允许）
cat > "$TMP_DIR/order-failed.events" <<'EOF'
event: run.started
data: {"runId":"r-4"}

event: tool.started
data: {"runId":"r-4","toolCallId":"t-2","toolName":"estimatePrice"}

event: tool.completed
data: {"runId":"r-4","toolCallId":"t-2","toolName":"estimatePrice","success":false,"durationMs":3}

event: run.failed
data: {"runId":"r-4","errorCode":"ORDER_UNAVAILABLE","message":"订单服务暂不可用"}
EOF

# 反例：缺终态
cat > "$TMP_DIR/missing-terminal.events" <<'EOF'
event: run.started
data: {"runId":"r-5"}

event: message.delta
data: {"runId":"r-5","content":"你好"}
EOF

# 反例：终态不居尾
cat > "$TMP_DIR/terminal-not-last.events" <<'EOF'
event: run.started
data: {"runId":"r-6"}

event: run.completed
data: {"runId":"r-6"}

event: message.delta
data: {"runId":"r-6","content":"你好"}
EOF

# 反例：重复终态
cat > "$TMP_DIR/duplicate-terminal.events" <<'EOF'
event: run.started
data: {"runId":"r-7"}

event: run.completed
data: {"runId":"r-7"}

event: run.completed
data: {"runId":"r-7"}
EOF

# 反例：INTERNAL_ERROR 不属于 order-failed 白名单
cat > "$TMP_DIR/bad-code.events" <<'EOF'
event: run.started
data: {"runId":"r-8"}

event: run.failed
data: {"runId":"r-8","errorCode":"INTERNAL_ERROR","message":"内部错误"}
EOF

# 反例：违禁工具被调用（Fallback 场景不允许任何工具）
cat > "$TMP_DIR/forbidden-tool.events" <<'EOF'
event: run.started
data: {"runId":"r-9"}

event: tool.started
data: {"runId":"r-9","toolCallId":"t-3","toolName":"searchKnowledgeBase"}

event: run.completed
data: {"runId":"r-9"}
EOF

# 反例：助手正文未命中知识库关键词
cat > "$TMP_DIR/text-miss.events" <<'EOF'
event: run.started
data: {"runId":"r-10"}

event: message.delta
data: {"runId":"r-10","content":"这个问题我暂时无法回答"}

event: message.completed
data: {"runId":"r-10","messageId":103}

event: run.completed
data: {"runId":"r-10"}
EOF

# 反例：notify 缺失（ORDER 粘滞未触发）
cat > "$TMP_DIR/notify-miss.events" <<'EOF'
event: run.started
data: {"runId":"r-11"}

event: message.delta
data: {"runId":"r-11","content":"今天天气晴。"}

event: message.completed
data: {"runId":"r-11","messageId":104}

event: run.completed
data: {"runId":"r-11"}
EOF

# 反例：首事件不是 run.started
cat > "$TMP_DIR/first-event.events" <<'EOF'
event: message.delta
data: {"runId":"r-12","content":"你好"}

event: run.completed
data: {"runId":"r-12"}
EOF

# 反例：DANGER 场景出现 message.completed（不应落库）
cat > "$TMP_DIR/forbidden-mc.events" <<'EOF'
event: run.started
data: {"runId":"r-13"}

event: message.delta
data: {"runId":"r-13","content":"您的命令不被支持，请换个内容继续吧。"}

event: message.completed
data: {"runId":"r-13","messageId":105}

event: run.completed
data: {"runId":"r-13"}
EOF

# 反例：成功流缺少 message.completed
cat > "$TMP_DIR/missing-mc.events" <<'EOF'
event: run.started
data: {"runId":"r-14"}

event: message.delta
data: {"runId":"r-14","content":"发票请前往订单详情页申请"}

event: run.completed
data: {"runId":"r-14"}
EOF

# 正例：场景 1 通过
run_case support-pass zero "$TMP_DIR/support.events" \
  completed searchKnowledgeBase '' '' '订单详情页,邮箱' 1 0
# 正例：DANGER 不落库流通过（含 message.delta 关键词 + 禁工具 + 禁 message.completed）
run_case danger-pass zero "$TMP_DIR/danger.events" \
  completed '' '*' '' '您的命令不被支持，请换个内容继续吧。' 0 1
# 正例：ORDER 粘滞 notify 通过
run_case sticky-pass zero "$TMP_DIR/sticky.events" \
  completed '' '' '当前处于下单流程，如果您想换个话题，请新建对话。' '好的' 1 0
# 正例：order-failed 终态通过（completed-or-order-failed 且要求 message.completed 仅在成功流生效）
run_case order-failed-pass zero "$TMP_DIR/order-failed.events" \
  completed-or-order-failed '' '' '' '' 1 0
# 正例：order-failed 终态下正文关键词断言被跳过（run.failed 无助手正文）
run_case order-failed-text-skip-pass zero "$TMP_DIR/order-failed.events" \
  completed-or-order-failed '' '' '' '价格,估算' 1 0
# 反例：缺终态
run_case missing-terminal-fail nonzero "$TMP_DIR/missing-terminal.events" \
  completed '' '' '' '' 1 0
# 反例：终态不居尾
run_case terminal-not-last-fail nonzero "$TMP_DIR/terminal-not-last.events" \
  completed '' '' '' '' 1 0
# 反例：重复终态
run_case duplicate-terminal-fail nonzero "$TMP_DIR/duplicate-terminal.events" \
  completed '' '' '' '' 1 0
# 反例：INTERNAL_ERROR 不在 order-failed 白名单
run_case bad-code-fail nonzero "$TMP_DIR/bad-code.events" \
  completed-or-order-failed '' '' '' '' 1 0
# 反例：Fallback 场景出现工具
run_case forbidden-tool-fail nonzero "$TMP_DIR/forbidden-tool.events" \
  completed '' '*' '' '' 1 0
# 反例：正文未命中关键词
run_case text-miss-fail nonzero "$TMP_DIR/text-miss.events" \
  completed '' '' '' '订单详情页,邮箱' 1 0
# 反例：粘滞 notify 缺失
run_case notify-miss-fail nonzero "$TMP_DIR/notify-miss.events" \
  completed '' '' '当前处于下单流程' '' 1 0
# 反例：首事件不是 run.started
run_case first-event-fail nonzero "$TMP_DIR/first-event.events" \
  completed '' '' '' '' 1 0
# 反例：DANGER 流出现 message.completed
run_case forbidden-mc-fail nonzero "$TMP_DIR/forbidden-mc.events" \
  completed '' '*' '' '您的命令不被支持' 0 1
# 反例：成功流缺少 message.completed
run_case missing-mc-fail nonzero "$TMP_DIR/missing-mc.events" \
  completed searchKnowledgeBase '' '' '订单详情页' 1 0

printf '[phase2-self-test] SELF-TEST PASS\n'
