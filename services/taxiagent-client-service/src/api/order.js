import request from './request'

/** POST /api/orders/page - 分页查询订单 {page,size,statusList[]} */
export function getOrders({ page = 1, size = 20, statusList = [] } = {}) {
  return request.post('/orders/page', { page, size, statusList })
}

/** GET /api/orders/{orderId} - 详情 */
export function getOrderDetail(orderId, options = {}) {
  return request.get(`/orders/${orderId}`, options)
}

/** GET /api/orders/routes/{traceId} → {traceId, estPolyline, realPolyline}；polyline 为 "lng,lat;lng,lat" 分号分段；404=ORDER_NOT_FOUND、403=非本人轨迹 */
export function getOrderRoute(traceId, options = {}) {
  return request.get(`/orders/routes/${encodeURIComponent(traceId)}`, options)
}

/** GET /api/orders/my/ongoing - 进行中订单 id 列表（createTime 降序） */
export function getOngoingOrders(options = {}) {
  return request.get('/orders/my/ongoing', options)
}

/** POST /api/orders/{orderId}/pay */
export function payOrder(orderId, payChannel = 1, tradeNo = '') {
  return request.post(`/orders/${orderId}/pay`, { payChannel, tradeNo })
}

/** POST /api/orders/{orderId}/cancel */
export function cancelOrder(orderId, reason = '用户取消') {
  return request.post(`/orders/${orderId}/cancel`, { cancelRole: 1, cancelReason: reason })
}
