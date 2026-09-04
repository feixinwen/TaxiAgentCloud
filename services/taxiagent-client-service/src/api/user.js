import request from './request'

/** GET /api/users/current - Auth+User 聚合资料（auth 服务） */
export function getCurrentUser() {
  return request.get('/users/current')
}

/** POST /api/users/current/update */
export function updateCurrentUser(data) {
  return request.post('/users/current/update', data)
}

/** POST /api/users/current/password/reset - 成功后全部会话撤销，需重新登录 */
export function resetCurrentUserPassword(password) {
  return request.post('/users/current/password/reset', { password })
}

// ─── 位置 ───

/** GET /api/users/loc → {latitude,longitude,address}（字符串）；从未设置时 404，skipErrorMessage 由调用方静默处理 */
export function getUserLocation() {
  return request.get('/users/loc', { skipErrorMessage: true })
}

/** POST /api/users/loc —— body {latitude, longitude, address}，值均为字符串 */
export function saveUserLocation(location) {
  return request.post('/users/loc', location)
}

// ─── POI ───

/** GET /api/users/poi → PoiView[] */
export function listPOIs() {
  return request.get('/users/poi')
}

/** POST /api/users/poi */
export function createPOI(data) {
  return request.post('/users/poi', data)
}

/** PUT /api/users/poi/{id} —— 云版 id 在路径！（单体在 body） */
export function updatePOI(data) {
  return request.put(`/users/poi/${data.id}`, data)
}

/** DELETE /api/users/poi/{id} */
export function deletePOI(id) {
  return request.delete(`/users/poi/${id}`)
}
