import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { gateRefresh } from './refreshGate'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

request.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.session?.accessToken) {
    config.headers['Authorization'] = `Bearer ${userStore.session.accessToken}`
  }
  return config
})

/** 这些错误码直接登出 */
const FORCE_LOGOUT_CODES = ['TOKEN_REVOKED', 'ACCOUNT_DISABLED']
/** /auth 自身（登录/刷新/登出）出错不走刷新循环 */
const AUTH_PATH_PREFIX = '/auth/'

/** 裸 axios 直打刷新端点：绕开本实例的请求拦截器，避免把过期 Access Token 再带上去 */
async function refreshOnce(userStore) {
  const refreshToken = userStore.session?.refreshToken
  if (!refreshToken) throw new Error('NO_REFRESH_TOKEN')
  const resp = await axios.post('/api/auth/refresh', { refreshToken })
  userStore.updateTokens(resp.data)
  return resp.data.accessToken
}

function forceLogout() {
  const userStore = useUserStore()
  userStore.logout()
  if (!location.pathname.startsWith('/auth')) location.href = '/auth'
}

request.interceptors.response.use(
  (resp) => resp.data,
  async (error) => {
    const response = error?.response
    const config = error?.config || {}

    if (
      response?.status === 401 &&
      !config.__retried &&
      !String(config.url || '').startsWith(AUTH_PATH_PREFIX)
    ) {
      try {
        await gateRefresh(() => refreshOnce(useUserStore()))
        config.__retried = true
        return request(config)
      } catch (refreshError) {
        forceLogout()
        return Promise.reject(error)
      }
    }

    if (response?.data?.code && FORCE_LOGOUT_CODES.includes(response.data.code)) {
      forceLogout()
    }

    // per-request 静默标记：用于"404 是正常业务态"的接口（如 GET /users/loc 从未设置）
    if (!config.skipErrorMessage) {
      ElMessage.error(response?.data?.message || error.message || '请求失败')
    }
    return Promise.reject(error)
  }
)

export default request
