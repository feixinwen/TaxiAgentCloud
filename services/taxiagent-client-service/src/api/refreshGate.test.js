import { describe, it, expect, beforeEach } from 'vitest'
import { gateRefresh, resetRefreshGate } from '@/api/refreshGate'

beforeEach(() => resetRefreshGate())

it('并发 401 共享同一次 doRefresh', async () => {
  let calls = 0
  const p1 = gateRefresh(async () => { calls++; await new Promise(r => setTimeout(r, 20)); return 'T2' })
  const p2 = gateRefresh(async () => { calls++; return 'SHOULD_NOT_RUN' })
  expect(await p1).toBe('T2')
  expect(await p2).toBe('T2')
  expect(calls).toBe(1)
})

it('刷新完成后下一次调用发起新的刷新', async () => {
  let calls = 0
  await gateRefresh(async () => { calls++ })
  await gateRefresh(async () => { calls++ })
  expect(calls).toBe(2)
})

it('失败同样被共享且释放门', async () => {
  let calls = 0
  const boom = gateRefresh(async () => { calls++; throw new Error('x') }).catch(e => e.message)
  const boom2 = gateRefresh(async () => 'should_not_run').catch(e => e.message)
  expect(await boom).toBe('x')
  expect(await boom2).toBe('x')
  expect(calls).toBe(1)
  // 门已释放：再来一次是新执行
  expect(await gateRefresh(async () => 'fresh')).toBe('fresh')
})
