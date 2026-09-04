import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import axios from 'axios'

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
}))

import { ElMessage } from 'element-plus'
import request from '@/api/request'
import { useUserStore } from '@/stores/user'
import { resetRefreshGate } from '@/api/refreshGate'

const SESSION = { accessToken: 'AT1', refreshToken: 'RT1', userId: 'u1', username: 'a', role: 'USER' }

/**
 * 同一 handler 同时挂到 request 实例与全局 axios（后者服务裸 axios.post('/api/auth/refresh')，
 * 否则它会走真实 XHR 导致测试态网络错误混入断言）。
 * handler(call, config)：call 为 {url, authorization}，返回响应对象或抛出。
 */
function useAdapter(handler) {
  const calls = []
  const adapter = async (config) => {
    const call = { method: config.method, url: config.url, authorization: config.headers?.Authorization || '' }
    calls.push(call)
    const out = await handler(call, config)
    if (out && out.__reject) {
      const err = new Error(out.message || 'Request failed')
      err.response = { status: out.status, data: out.data, statusText: 'ERR', headers: {} }
      err.config = config
      throw err
    }
    return { status: 200, statusText: 'OK', headers: {}, ...out, config }
  }
  request.defaults.adapter = adapter
  axios.defaults.adapter = adapter
  return calls
}
const ok = (data) => ({ data })
const bad = (status, data) => ({ __reject: true, status, data })

beforeEach(() => {
  localStorage.clear()
  resetRefreshGate()
  setActivePinia(createPinia())
  useUserStore().setSession({ ...SESSION })
  vi.clearAllMocks()
})

it('自动注入 Bearer 头且解包 resp.data', async () => {
  const calls = useAdapter(() => ok({ hello: 'world' }))
  const body = await request.get('/orders/my/ongoing')
  expect(body).toEqual({ hello: 'world' })
  expect(calls[0].authorization).toBe('Bearer AT1')
})

it('401 → 刷新一次 → 用新 Token 重放，全程静默', async () => {
  const calls = useAdapter((call) => {
    if (call.url === '/orders/page' && call.authorization === 'Bearer AT1') return bad(401, { code: 'UNAUTHORIZED', message: 'expired' })
    if (call.url === '/api/auth/refresh') return ok({ accessToken: 'AT2', refreshToken: 'RT2', userId: 'u1', username: 'a', role: 'USER' })
    if (call.url === '/orders/page' && call.authorization === 'Bearer AT2') return ok([{ orderId: 'o1' }])
    return ok({})
  })
  const body = await request.post('/orders/page', { page: 1 })
  expect(body).toEqual([{ orderId: 'o1' }])
  expect(calls.filter(c => c.url === '/api/auth/refresh').length).toBe(1)
  expect(useUserStore().session.accessToken).toBe('AT2')
  expect(ElMessage.error).not.toHaveBeenCalled()
})

it('并发两个 401 只触发一次刷新', async () => {
  const gate = new Promise(r => setTimeout(r, 30))
  const calls = useAdapter(async (call) => {
    if (call.url === '/api/auth/refresh') { await gate; return ok({ accessToken: 'AT2', refreshToken: 'RT2', userId: 'u1', username: 'a', role: 'USER' }) }
    if (call.authorization === 'Bearer AT1') return bad(401, { code: 'UNAUTHORIZED', message: 'expired' })
    return ok('done-' + call.url)
  })
  const results = await Promise.all([
    request.get('/users/current'),
    request.get('/users/poi'),
  ])
  expect(calls.filter(c => c.url === '/api/auth/refresh').length).toBe(1)
  expect(results.sort()).toEqual(['done-/users/current', 'done-/users/poi'].sort())
})

it('刷新失败 → 登出并跳 /auth，不弹错', async () => {
  delete window.location
  window.location = { pathname: '/orders' }
  useAdapter((call) => {
    if (call.url === '/api/auth/refresh') return bad(401, { code: 'INVALID_REFRESH_TOKEN', message: 'revoked' })
    if (call.authorization === 'Bearer AT1') return bad(401, { code: 'UNAUTHORIZED', message: 'expired' })
    return ok({})
  })
  await expect(request.get('/users/current')).rejects.toBeTruthy()
  expect(useUserStore().isLoggedIn).toBe(false)
  expect(window.location.href).toBe('/auth')
})

it('/auth 自身 401 不进刷新循环，直接弹错', async () => {
  const calls = useAdapter((call) => {
    if (call.url === '/auth/login/password') return bad(401, { code: 'INVALID_CREDENTIALS', message: 'bad credentials' })
    return ok({})
  })
  await expect(request.post('/auth/login/password', {})).rejects.toBeTruthy()
  expect(calls.some(c => c.url === '/api/auth/refresh')).toBe(false)
  expect(ElMessage.error).toHaveBeenCalledWith('bad credentials')
})
