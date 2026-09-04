#!/usr/bin/env python3
"""Agent SSE 流契约断言（阶段 2/3 冒烟共用，被 smoke-agent-phase2.sh、smoke-agent-phase3.sh 与自测脚本复用）。

用法:
    sse_assert.py <events-file> <terminal-mode> <require-tools> <forbid-tools> \\
                  <notify-kws> <text-kws> <require-message-completed> <forbid-message-completed> \\
                  [require-events]

参数说明:
- events-file: curl --output 保存的原始 SSE 响应（逐事件块解析，跳过 heartbeat/ping）
- terminal-mode:
  - completed: 唯一且最后的终态必须是 run.completed
  - completed-or-order-failed: 允许 run.completed，或 run.failed 且 errorCode 为
    ORDER_UNAVAILABLE/ORDER_REQUEST_REJECTED（Daily/Order Agent 的 estimatePrice 上游拒绝路径）
- require-tools / forbid-tools: 逗号分隔工具名；forbid-tools 传 "*" 表示禁止一切工具调用
- notify-kws / text-kws: 逗号分隔关键词（分别对 notify 正文聚合与 message.delta 正文聚合做子串匹配，
  命中任一即通过；空串跳过该维度断言）
- require-message-completed / forbid-message-completed: 1 或 0（前者仅在终态为 run.completed 时生效）
- require-events（可选）: 逗号分隔的事件名（如 confirm），流中必须至少出现一次

退出码 0 通过，1 失败并打印原因。脚本不打印 Token、消息正文或任何机密内容。
"""
import json
import sys


def parse_events(path):
    """把 SSE 响应文件解析为 [(event_name, payload), ...]，payload 为 JSON 对象或原始字符串。"""
    with open(path, encoding="utf-8") as source:
        raw = source.read().replace("\r\n", "\n").replace("\r", "\n")
    events = []
    for block in raw.split("\n\n"):
        if not block.strip():
            continue
        name = None
        data_lines = []
        for line in block.splitlines():
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                data_lines.append(line[5:].lstrip())
        if not name or name in {"heartbeat", "ping"}:
            continue
        data = "\n".join(data_lines)
        payload = None
        if data:
            try:
                payload = json.loads(data)
            except json.JSONDecodeError:
                payload = data
        events.append((name, payload))
    return events


def keywords(csv):
    """逗号分隔关键词转列表；空串返回空列表（跳过断言）。"""
    if not csv:
        return []
    return [item.strip() for item in csv.split(",") if item.strip()]


def main(argv):
    (events_path, terminal_mode, require_tools_csv, forbid_tools_csv,
     notify_kws_csv, text_kws_csv, require_mc, forbid_mc) = argv[1:9]
    require_events = keywords(argv[9] if len(argv) > 9 else "")
    require_tools = keywords(require_tools_csv)
    forbid_tools = keywords(forbid_tools_csv)
    notify_kws = keywords(notify_kws_csv)
    text_kws = keywords(text_kws_csv)
    forbid_all_tools = forbid_tools_csv.strip() == "*"
    require_mc = require_mc == "1"
    forbid_mc = forbid_mc == "1"
    order_failed_codes = {"ORDER_UNAVAILABLE", "ORDER_REQUEST_REJECTED"}

    def fail(reason):
        print(f"[sse-assert] FAIL: {reason}")
        sys.exit(1)

    events = parse_events(events_path)
    if not events:
        fail("无任何业务事件")
    if events[0][0] != "run.started":
        fail(f"第一条业务事件必须是 run.started，实际为 {events[0][0]}")

    terminal_indexes = [
        index for index, (name, _) in enumerate(events)
        if name in {"run.completed", "run.failed"}
    ]
    if len(terminal_indexes) != 1:
        fail("SSE 流必须恰好包含一个终态事件（run.completed/run.failed）")
    terminal_index = terminal_indexes[0]
    if terminal_index != len(events) - 1:
        fail("终态事件必须是最后一条业务事件")
    terminal_name, terminal_payload = events[terminal_index]

    tool_names = [
        payload.get("toolName")
        for name, payload in events
        if name == "tool.started" and isinstance(payload, dict)
    ]
    notify_text = " ".join(
        str(payload.get("content", ""))
        for name, payload in events
        if name == "notify" and isinstance(payload, dict)
    )
    assistant_text = "".join(
        str(payload.get("content", ""))
        for name, payload in events
        if name == "message.delta" and isinstance(payload, dict)
    )
    has_message_completed = any(name == "message.completed" for name, _ in events)

    if terminal_mode == "completed":
        if terminal_name != "run.completed":
            fail(f"预期终态 run.completed，实际为 {terminal_name}")
    elif terminal_mode == "completed-or-order-failed":
        if terminal_name == "run.failed":
            code = terminal_payload.get("errorCode") if isinstance(terminal_payload, dict) else None
            if code not in order_failed_codes:
                fail(f"run.failed 必须携带 ORDER_UNAVAILABLE/ORDER_REQUEST_REJECTED，"
                     f"实际 errorCode={code}")
    else:
        fail(f"未知终态模式 {terminal_mode}")

    for tool in require_tools:
        if tool not in tool_names:
            fail(f"必须调用工具 {tool}，实际工具列表 {tool_names}")
    if forbid_all_tools and tool_names:
        fail(f"禁止调用任何工具，实际工具列表 {tool_names}")
    for tool in forbid_tools:
        if tool in tool_names:
            fail(f"禁止调用工具 {tool}，实际工具列表 {tool_names}")
    event_names = [name for name, _ in events]
    for event_name in require_events:
        if event_name not in event_names:
            fail(f"必须出现事件 {event_name}，实际事件列表 {event_names}")
    for keyword in notify_kws:
        if keyword not in notify_text:
            fail(f"notify 事件正文必须包含 {keyword!r}")
    # 正文断言仅对成功流生效：run.failed 流没有助手正文（如 estimatePrice 上游拒绝路径）
    if terminal_name == "run.completed" and text_kws \
            and not any(keyword in assistant_text for keyword in text_kws):
        fail(f"助手正文必须命中关键词之一 {text_kws}，实际正文长度 {len(assistant_text)}")
    if require_mc and terminal_name == "run.completed" and not has_message_completed:
        fail("成功流必须包含 message.completed")
    if forbid_mc and has_message_completed:
        fail("该场景不允许出现 message.completed")

    print(f"[sse-assert] OK terminal={terminal_name} tools={tool_names or '无'} "
          f"delta_len={len(assistant_text)} notify_len={len(notify_text)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
