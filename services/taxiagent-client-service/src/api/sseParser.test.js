import { describe, it, expect } from 'vitest'
import { createSseParser } from '@/api/sseParser'

const enc = (s) => new TextEncoder().encode(s)

it('解析单个标准块', () => {
  const p = createSseParser()
  const evs = p.feed(enc('event: message.delta\nid: 1\ndata: {"content":"hi"}\n\n'))
  expect(evs).toEqual([{ event: 'message.delta', id: '1', data: { content: 'hi' } }])
})

it('块被网络分片切断后仍正确拼装', () => {
  const p = createSseParser()
  const a = p.feed(enc('event: run.failed\ndata: {"errorCode":"MODEL_'))
  expect(a).toEqual([])
  const b = p.feed(enc('UNAVAILABLE"}\n\n'))
  expect(b[0].data.errorCode).toBe('MODEL_UNAVAILABLE')
})

it('多行 data 以 \n 合并；缺 event 名默认 message；非法 JSON 保原文', () => {
  const p = createSseParser()
  const evs = p.feed(enc('data: part1\ndata: part2\n\n'))
  expect(evs[0].event).toBe('message')
  expect(evs[0].data).toBe('part1\npart2')
})

it('心跳空壳与 CRLF 兼容', () => {
  const p = createSseParser()
  const evs = p.feed(enc('event: heartbeat\r\ndata: {"runId":"r"}\r\n\r\n'))
  expect(evs[0].event).toBe('heartbeat')
})

it('flush 收尾未成块的尾包', () => {
  const p = createSseParser()
  p.feed(enc('event: notify\ndata: {"content":"tail"}'))
  const tail = p.flush()
  expect(tail[0].event).toBe('notify')
})
