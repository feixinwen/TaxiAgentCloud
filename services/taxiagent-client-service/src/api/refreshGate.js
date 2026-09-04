let refreshingPromise = null

/**
 * 并发 401 时只放行一次刷新；后来者挂到同一个 Promise 上，
 * 避免 Refresh Token 轮换竞态（第二次 refresh 用刚轮换掉的旧 RT 必然 401）。
 */
export function gateRefresh(doRefresh) {
  if (!refreshingPromise) {
    refreshingPromise = Promise.resolve()
      .then(doRefresh)
      .finally(() => {
        refreshingPromise = null
      })
  }
  return refreshingPromise
}

/** 仅测试使用 */
export function resetRefreshGate() {
  refreshingPromise = null
}
