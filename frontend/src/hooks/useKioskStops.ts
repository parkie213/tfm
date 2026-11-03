import { useState } from 'react'
import type { Stop } from '../proto/tfm/v1/stops_pb'
import type { ShopLocation } from './useShopLocation'

const STORAGE_KEY = 'tfm.tv.selectedStops'
interface Selection { locationKey: string; stops: Stop[] }

function loadSelection(): Selection | null {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null')
    if (!value || typeof value.locationKey !== 'string' || !Array.isArray(value.stops) ||
      value.stops.length === 0 || value.stops.length > 10) return null
    const valid = value.stops.every((s: Stop) => s && typeof s.atcoCode === 'string' && !!s.atcoCode &&
      typeof s.name === 'string' && typeof s.indicator === 'string' &&
      Number.isFinite(s.lat) && Number.isFinite(s.lng) && Math.abs(s.lat) <= 90 && Math.abs(s.lng) <= 180)
    return valid ? value as Selection : null
  } catch { return null }
}

/** Stores the venue's explicit stop selection alongside the location it belongs to. */
export function useKioskStops(location: ShopLocation | null) {
  const [selection, setSelection] = useState<Selection | null>(loadSelection)
  const locationKey = location ? `${location.lat},${location.lng}` : null
  const stops = selection?.locationKey === locationKey ? selection.stops : null
  function saveStops(stops: Stop[]) {
    if (!locationKey || stops.length === 0 || stops.length > 10) return
    const next = { locationKey, stops }
    setSelection(next)
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(next)) } catch { /* session still works */ }
  }
  return { selectedStops: stops, saveStops }
}
