import { describe, it, expect, beforeEach, vi } from 'vitest'
import { parsePolyline, parseLngLatFromUnknown, getEnvString } from './amap'

describe('parsePolyline', () => {
  it('按分号分段且 lng 在前', () => {
    expect(parsePolyline('121.1,31.1;121.2,31.2')).toEqual([
      [121.1, 31.1],
      [121.2, 31.2],
    ])
  })

  it('容忍多余空白', () => {
    expect(parsePolyline(' 121.1, 31.1 ; 121.2,31.2 ')).toEqual([
      [121.1, 31.1],
      [121.2, 31.2],
    ])
  })

  it('跳过非法分段', () => {
    expect(parsePolyline('121.1,31.1;;abc;121.2,31.2')).toEqual([
      [121.1, 31.1],
      [121.2, 31.2],
    ])
  })

  it('空串返回空数组', () => {
    expect(parsePolyline('')).toEqual([])
  })
})

describe('parseLngLatFromUnknown', () => {
  it('"lng,lat" 字符串', () => {
    expect(parseLngLatFromUnknown('121.47,31.23')).toEqual({ lng: 121.47, lat: 31.23 })
  })

  it('[lng, lat] 数组', () => {
    expect(parseLngLatFromUnknown([121.47, 31.23])).toEqual({ lng: 121.47, lat: 31.23 })
  })

  it('AMap.LngLat 对象（getLng/getLat 方法）', () => {
    expect(
      parseLngLatFromUnknown({ getLng: () => 121.47, getLat: () => 31.23 }),
    ).toEqual({ lng: 121.47, lat: 31.23 })
  })

  it('{lng, lat} 普通对象', () => {
    expect(parseLngLatFromUnknown({ lng: 121.47, lat: 31.23 })).toEqual({ lng: 121.47, lat: 31.23 })
  })

  it('非法输入返回 null', () => {
    expect(parseLngLatFromUnknown(null)).toBeNull()
    expect(parseLngLatFromUnknown('not-a-coord')).toBeNull()
    expect(parseLngLatFromUnknown({ lng: 'x', lat: 1 })).toBeNull()
  })
})

describe('getEnvString', () => {
  beforeEach(() => {
    vi.unstubAllEnvs()
  })

  it('返回去空格后的值', () => {
    vi.stubEnv('VITE_AMAP_KEY', '  some-key  ')
    expect(getEnvString('VITE_AMAP_KEY')).toBe('some-key')
  })

  it('缺失或空串返回 undefined', () => {
    vi.stubEnv('VITE_AMAP_KEY', '')
    expect(getEnvString('VITE_AMAP_KEY')).toBeUndefined()
  })
})
