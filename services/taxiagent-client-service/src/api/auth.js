import request from './request'

/** POST /api/auth/email-code - scene: REGISTER | LOGIN | RESET_PASSWORD；429 冷却 */
export function sendEmailCode(email, scene = 'REGISTER') {
  return request.post('/auth/email-code', { email, scene })
}

/** POST /api/auth/register - 成功直接返回统一会话响应体（无需再登录） */
export function register(data) {
  return request.post('/auth/register', data)
}

/** GET /api/auth/username/available?username= → {available:boolean} */
export function checkUsername(username) {
  return request.get('/auth/username/available', { params: { username } })
}

/** POST /api/auth/login/email-code - 仅 USER 角色 */
export function loginByEmailCode(email, code) {
  return request.post('/auth/login/email-code', { email, code })
}

/** POST /api/auth/login/password - login 支持 邮箱 | 用户名(USER) | username#role(后台) */
export function loginByPassword(login, password) {
  return request.post('/auth/login/password', { login, password })
}

/** POST /api/auth/password/reset - 忘记密码；成功撤销全部会话 */
export function resetPassword({ email, code, newPassword }) {
  return request.post('/auth/password/reset', { email, code, newPassword })
}

/** POST /api/auth/logout - 撤销 RT + 当前 AT jti 黑名单 */
export function logoutApi() {
  return request.post('/auth/logout')
}
