/**
 * 标准 SSE 行协议解析器（替代单体的括号深度 JSON 提取法）：
 * 累积文本直到空行成块；块内 event:/id:/data: 各取其一（data 可多行合并）；
 * JSON.parse 失败时 data 退化为原始字符串。
 */
export function createSseParser() {
  const decoder = new TextDecoder()
  let buffer = ''
  let lines = []

  function emitBlock() {
    if (!lines.length) return null
    let eventName = ''
    let id = null
    const dataLines = []
    for (const line of lines) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      else if (line.startsWith('id:')) id = line.slice(3).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    lines = []
    if (!eventName && dataLines.length === 0) return null
    const raw = dataLines.join('\n')
    let data = raw
    try {
      data = JSON.parse(raw)
    } catch {
      /* 保持原文 */
    }
    return { event: eventName || 'message', data, id }
  }

  return {
    feed(chunk) {
      buffer += decoder.decode(chunk, { stream: true })
      const events = []
      let idx
      while ((idx = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, idx).replace(/\r$/, '')
        buffer = buffer.slice(idx + 1)
        if (line === '') {
          const ev = emitBlock()
          if (ev) events.push(ev)
        } else {
          lines.push(line)
        }
      }
      return events
    },
    flush() {
      const tail = decoder.decode()
      if (tail) buffer += tail
      const last = emitBlock()
      return last ? [last] : []
    },
  }
}
