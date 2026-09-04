import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '@/stores/user'

const SESSION_BODY = {
  accessToken: 'at-1',
  refreshToken: 'rt-1',
  tokenType: 'Bearer',
  expiresInSec: 900,
  refreshExpiresInSec: 604800,
  userId: '1234567890123456789',
  username: 'alice',
  role: 'USER',
}

describe('user store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('setSession 持久化统一会话体并暴露登录态', () => {
    const store = useUserStore()
    store.setSession(SESSION_BODY)
    expect(store.isLoggedIn).toBe(true)
    expect(store.role).toBe('USER')
    expect(JSON.parse(localStorage.getItem('session'))).toMatchObject({ accessToken: 'at-1' })
  })

  it('userId 保持字符串不被数值化', () => {
    const store = useUserStore()
    store.setSession(SESSION_BODY)
    expect(typeof store.session.userId).toBe('string')
  })

  it('updateTokens 只替换双 Token 其余字段不动', () => {
    const store = useUserStore()
    store.setSession(SESSION_BODY)
    store.updateTokens({ accessToken: 'at-2', refreshToken: 'rt-2' })
    expect(store.session.accessToken).toBe('at-2')
    expect(store.session.refreshToken).toBe('rt-2')
    expect(store.session.username).toBe('alice')
  })

  it('logout 清空内存与存储', () => {
    const store = useUserStore()
    store.setSession(SESSION_BODY)
    store.logout()
    expect(store.isLoggedIn).toBe(false)
    expect(localStorage.getItem('session')).toBeNull()
  })

  it('重新构建 pinia 后能从 localStorage 恢复会话', () => {
    localStorage.setItem('session', JSON.stringify(SESSION_BODY))
    setActivePinia(createPinia())
    const store = useUserStore()
    expect(store.isLoggedIn).toBe(true)
    expect(store.username).toBe('alice')
  })
})
