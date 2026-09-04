import { onBeforeUnmount, ref } from 'vue'
import { loadAmap, loadAmapPlugins, parseLngLatFromUnknown, parsePolyline } from '@/utils/amap'

const ROUTE_STYLES = {
  estimated: { strokeColor: '#22c55e', strokeWeight: 6 },
  real: { strokeColor: '#f97316', strokeWeight: 7 },
}

const ROUTE_LINE_BASE = {
  strokeOpacity: 1,
  lineJoin: 'round',
  lineCap: 'round',
  isOutline: true,
  outlineColor: '#ffffff',
  borderWeight: 1,
  showDir: true,
}

const START_ICON = 'https://webapi.amap.com/theme/v1.3/markers/n/mark_g.png'
const END_ICON = 'https://webapi.amap.com/theme/v1.3/markers/n/mark_b.png'

/**
 * 聊天地图：定位态（用户位置 marker + 点选设位）与路线态（估价绿线 / 实际橙线）共用一张图。
 * 高德加载失败（无 key / 网络不通）时 mapError 置位，视图据此隐藏地图栏降级纯聊天。
 */
export function useChatMap(mapEl) {
  const mapBooting = ref(false)
  const mapError = ref(null)
  const pickMode = ref(false)

  let AMap = null
  let map = null
  let userMarker = null
  let startMarker = null
  let endMarker = null
  let routeLine = null
  let mapClickHandler = null

  async function bootstrapMap(initialCenter) {
    mapBooting.value = true
    mapError.value = null
    try {
      AMap = await loadAmap()
      if (!mapEl.value) return false
      map = new AMap.Map(mapEl.value, {
        zoom: initialCenter ? 16 : 11,
        center: initialCenter || [121.4737, 31.2304],
      })
      return true
    } catch (e) {
      mapError.value = e?.message || '地图初始化失败'
      return false
    } finally {
      mapBooting.value = false
    }
  }

  /** 浏览器定位（拒绝/失败返回 null，由调用方 fallback 城市中心） */
  function locateCurrentPosition() {
    if (!map || !AMap) return Promise.resolve(null)
    return loadAmapPlugins(AMap, ['AMap.Geolocation']).then(
      () =>
        new Promise((resolve) => {
          const geo = new AMap.Geolocation({ enableHighAccuracy: true, timeout: 8000 })
          geo.getCurrentPosition((status, result) => {
            if (status !== 'complete') {
              resolve(null)
              return
            }
            const parsed = parseLngLatFromUnknown(result?.position)
            resolve(parsed ? { lng: parsed.lng, lat: parsed.lat } : null)
          })
        }),
    )
  }

  function setUserLocation(point, { pan = true } = {}) {
    if (!map || !AMap) return
    if (!userMarker) {
      userMarker = new AMap.Marker({ position: point, anchor: 'bottom-center' })
      userMarker.setMap(map)
    } else {
      userMarker.setPosition(point)
    }
    if (pan) {
      map.setCenter(point)
      map.setZoom(16)
    }
  }

  function startPickMode(onPicked) {
    if (!map) return false
    pickMode.value = true
    if (mapClickHandler) return true
    mapClickHandler = (e) => {
      const parsed = parseLngLatFromUnknown(e?.lnglat)
      if (parsed) onPicked?.(parsed.lng, parsed.lat)
    }
    map.on('click', mapClickHandler)
    return true
  }

  function stopPickMode() {
    pickMode.value = false
    if (!map || !mapClickHandler) return
    map.off('click', mapClickHandler)
    mapClickHandler = null
  }

  /** 逆地理编码 → 地址文本（失败返回空串，不阻塞点选保存） */
  async function reverseGeocode(lng, lat) {
    if (!AMap) return ''
    await loadAmapPlugins(AMap, ['AMap.Geocoder'])
    return new Promise((resolve) => {
      const geocoder = new AMap.Geocoder()
      geocoder.getAddress([lng, lat], (status, result) => {
        if (status !== 'complete') {
          resolve('')
          return
        }
        resolve(result?.regeocode?.formattedAddress || '')
      })
    })
  }

  /** 估价路线：绿线 + 绿/蓝起终点 marker，setFitView 包住全程；polyline 缺失时仅画 marker */
  function showEstimateRoute({ start, end, polyline } = {}) {
    drawRoute(ROUTE_STYLES.estimated, start, end, polyline)
  }

  /** 实际路线：橙线覆盖绿线，起终点 marker 沿用路线端点 */
  function showRealRoute(polyline) {
    drawRoute(ROUTE_STYLES.real, null, null, polyline)
  }

  function drawRoute(style, startPos, endPos, polyline) {
    if (!map || !AMap) return
    const points = parsePolyline(polyline)
    const hasLine = points.length >= 2
    const start = startPos || (hasLine ? points[0] : null)
    const end = endPos || (hasLine ? points[points.length - 1] : null)
    if (!hasLine && !start && !end) return

    const overlays = []
    if (start) {
      setPointMarker('start', start)
      overlays.push(startMarker)
    }
    if (end) {
      setPointMarker('end', end)
      overlays.push(endMarker)
    }

    if (hasLine) {
      if (!routeLine) {
        routeLine = new AMap.Polyline({ path: points, ...style, ...ROUTE_LINE_BASE })
        routeLine.setMap(map)
      } else {
        routeLine.setPath(points)
        routeLine.setOptions({ ...style, ...ROUTE_LINE_BASE })
      }
      overlays.push(routeLine)
    }
    map.setFitView(overlays, false, [60, 60, 60, 60])
  }

  function setPointMarker(kind, point) {
    const icon = kind === 'start' ? START_ICON : END_ICON
    const existing = kind === 'start' ? startMarker : endMarker
    if (!existing) {
      const marker = new AMap.Marker({ position: point, icon, anchor: 'bottom-center' })
      marker.setMap(map)
      if (kind === 'start') startMarker = marker
      else endMarker = marker
    } else {
      existing.setPosition(point)
      existing.setIcon?.(icon)
    }
  }

  function clearRoute() {
    routeLine?.setMap(null)
    routeLine = null
    startMarker?.setMap(null)
    startMarker = null
    endMarker?.setMap(null)
    endMarker = null
  }

  /** 容器尺寸变化（折叠/展开）后让地图重新计算布局 */
  function resize() {
    map?.resize?.()
  }

  function destroy() {
    stopPickMode()
    map?.destroy()
    map = null
    userMarker = null
    startMarker = null
    endMarker = null
    routeLine = null
    AMap = null
  }

  onBeforeUnmount(destroy)

  return {
    mapBooting,
    mapError,
    pickMode,
    bootstrapMap,
    locateCurrentPosition,
    setUserLocation,
    startPickMode,
    stopPickMode,
    reverseGeocode,
    showEstimateRoute,
    showRealRoute,
    clearRoute,
    resize,
    destroy,
  }
}
