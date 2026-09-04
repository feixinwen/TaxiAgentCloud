import { defineStore } from 'pinia'

const SESSION_KEY = 'session'

function loadSession() {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  const session = ref(loadSession())

  const isLoggedIn = computed(() => !!session.value?.accessToken)
  const role = computed(() => session.value?.role || '')
  const username = computed(() => session.value?.username || '')

  function persist() {
    if (session.value) localStorage.setItem(SESSION_KEY, JSON.stringify(session.value))
    else localStorage.removeItem(SESSION_KEY)
  }

  /** 写入统一会话响应体（注册/登录/刷新共用形状） */
  function setSession(data) {
    session.value = data
    persist()
  }

  /** 静默刷新成功后仅替换双 Token */
  function updateTokens({ accessToken, refreshToken }) {
    if (!session.value) return
    session.value.accessToken = accessToken
    session.value.refreshToken = refreshToken
    persist()
  }

  function logout() {
    session.value = null
    persist()
  }

  return { session, isLoggedIn, role, username, setSession, updateTokens, logout }
})
