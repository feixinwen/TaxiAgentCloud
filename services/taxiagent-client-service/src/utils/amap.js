let amapPromise = null

/** 动态加载高德 JS API 2.0（key 缺失时 reject，调用方据此降级隐藏地图） */
export function loadAmap() {
  if (amapPromise) return amapPromise

  amapPromise = new Promise((resolve, reject) => {
    const key = getEnvString('VITE_AMAP_KEY')
    const securityJsCode = getEnvString('VITE_AMAP_SAFE_SECRET')
    if (!key || !securityJsCode) {
      reject(new Error('缺少高德地图配置：VITE_AMAP_KEY / VITE_AMAP_SAFE_SECRET'))
      return
    }

    const w = window
    if (w.AMap) {
      resolve(w.AMap)
      return
    }

    w._AMapSecurityConfig = { securityJsCode }

    const scriptId = 'amap-jsapi-2'
    const existing = document.getElementById(scriptId)
    if (existing) {
      existing.addEventListener('load', () => {
        if (w.AMap) resolve(w.AMap)
        else reject(new Error('AMap 加载完成但 window.AMap 缺失'))
      })
      existing.addEventListener('error', () => reject(new Error('高德地图 JSAPI 加载失败')))
      return
    }

    const script = document.createElement('script')
    script.id = scriptId
    script.type = 'text/javascript'
    script.async = true
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.Geolocation,AMap.Geocoder`
    script.onload = () => {
      if (w.AMap) resolve(w.AMap)
      else reject(new Error('AMap 加载完成但 window.AMap 缺失'))
    }
    script.onerror = () => reject(new Error('高德地图 JSAPI 加载失败'))
    document.head.appendChild(script)
  })

  return amapPromise
}

export function loadAmapPlugins(AMap, plugins) {
  return new Promise((resolve) => {
    AMap.plugin(plugins, () => resolve())
  })
}

/**
 * 解析轨迹 polyline："lng,lat;lng,lat;…" 分号分段、lng 在前（与高德一致）。
 * 返回 [[lng,lat], …]，非法分段跳过。
 */
export function parsePolyline(polyline) {
  if (typeof polyline !== 'string' || !polyline.trim()) return []
  const points = []
  for (const segment of polyline.split(';')) {
    const parsed = parseLngLatFromUnknown(segment)
    if (parsed) points.push([parsed.lng, parsed.lat])
  }
  return points
}

/** 归一化各种坐标形态：'lng,lat' 字符串 / [lng,lat] / {lng,lat} / AMap.LngLat（getLng/getLat） */
export function parseLngLatFromUnknown(input) {
  if (typeof input === 'string' && input.includes(',')) {
    const [lngRaw, latRaw] = input.split(',').map((value) => value.trim())
    const lng = toFiniteNumber(lngRaw)
    const lat = toFiniteNumber(latRaw)
    if (lng == null || lat == null) return null
    return { lng, lat }
  }

  if (Array.isArray(input) && input.length >= 2) {
    const lng = toFiniteNumber(input[0])
    const lat = toFiniteNumber(input[1])
    if (lng == null || lat == null) return null
    return { lng, lat }
  }

  if (!isRecord(input)) return null

  const lng = toFiniteNumber(input.lng) ?? toFiniteNumber(callMaybeNumber(input.getLng))
  const lat = toFiniteNumber(input.lat) ?? toFiniteNumber(callMaybeNumber(input.getLat))
  if (lng == null || lat == null) return null
  return { lng, lat }
}

export function getEnvString(key) {
  const raw = import.meta.env[key]
  return typeof raw === 'string' && raw.trim() ? raw.trim() : undefined
}

function isRecord(value) {
  return typeof value === 'object' && value !== null
}

function toFiniteNumber(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim()) {
    const n = Number(value)
    return Number.isFinite(n) ? n : null
  }
  return null
}

function callMaybeNumber(fn) {
  if (typeof fn !== 'function') return undefined
  try {
    return fn()
  } catch {
    return undefined
  }
}
