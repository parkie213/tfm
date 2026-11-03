import { useCallback, useEffect, useState } from 'react'
import { MELTON_TOWN_CENTRE } from '../constants/stops'

const STORAGE_KEY = 'tfm.tv.shopLocation'

export interface ShopLocation {
  lat: number
  lng: number
  /** Human-readable label shown under the "You are here" pin (the address the shop entered). */
  label: string
}

function isValid(v: unknown): v is ShopLocation {
  if (!v || typeof v !== 'object') return false
  const o = v as Record<string, unknown>
  return (
    typeof o.lat === 'number' &&
    typeof o.lng === 'number' &&
    Number.isFinite(o.lat) &&
    Number.isFinite(o.lng) &&
    typeof o.label === 'string'
  )
}

/** URL params win over storage so a shop can hard-pin a display via the kiosk URL (?lat&lng&label). */
function fromUrl(): ShopLocation | null {
  const p = new URLSearchParams(window.location.search)
  const lat = Number(p.get('lat'))
  const lng = Number(p.get('lng'))
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || (lat === 0 && lng === 0)) return null
  return { lat, lng, label: p.get('label') || 'You are here' }
}

function fromStorage(): ShopLocation | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    return isValid(parsed) ? parsed : null
  } catch {
    return null
  }
}

export interface UseShopLocationResult {
  location: ShopLocation | null
  /** Persist a chosen location (e.g. after geocoding an address). */
  setLocation: (loc: ShopLocation) => void
  /** Clear the saved pin and re-open the address prompt. */
  clear: () => void
}

/**
 * The shop's "You are here" pin. Set ONCE by the shopkeeper: it's read from the URL (kiosk override)
 * or localStorage, and persisted there after they submit an address. Null until one is chosen, which
 * the TV view uses to show the address-entry prompt.
 */
export function useShopLocation(): UseShopLocationResult {
  const [location, setLocationState] = useState<ShopLocation | null>(() => fromUrl() ?? fromStorage())

  // A URL-provided pin is authoritative — mirror it into storage so later loads (without the params)
  // keep it. Runs once; URL is read synchronously above for the first paint.
  useEffect(() => {
    const url = fromUrl()
    if (url) {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(url))
      } catch {
        /* private mode / quota — pin still lives in memory for this session */
      }
    }
  }, [])

  const setLocation = useCallback((loc: ShopLocation) => {
    setLocationState(loc)
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(loc))
    } catch {
      /* ignore persistence failure */
    }
  }, [])

  const clear = useCallback(() => {
    setLocationState(null)
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      /* ignore */
    }
  }, [])

  return { location, setLocation, clear }
}

export class GeocodeError extends Error {}

/**
 * Geocode a free-text address / postcode to a point via OpenStreetMap Nominatim. One-off lookup at
 * setup time (not per-render), biased to the Melton area and GB. Throws GeocodeError on no match.
 */
export async function geocodeAddress(query: string, signal?: AbortSignal): Promise<ShopLocation> {
  const q = query.trim()
  if (!q) throw new GeocodeError('Please enter an address or postcode')

  // ~12 km box around the town centre to bias (not restrict) results toward Melton.
  const d = 0.16
  const { lat, lng } = MELTON_TOWN_CENTRE
  const viewbox = `${lng - d},${lat + d},${lng + d},${lat - d}`
  const url =
    'https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=gb' +
    `&viewbox=${viewbox}&bounded=0&q=${encodeURIComponent(q)}`

  let res: Response
  try {
    res = await fetch(url, { signal, headers: { Accept: 'application/json' } })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new GeocodeError('Could not reach the address lookup service')
  }
  if (!res.ok) throw new GeocodeError(`Address lookup failed (${res.status})`)

  const results = (await res.json()) as Array<{ lat: string; lon: string; display_name: string }>
  if (!results.length) throw new GeocodeError(`No match for "${q}"`)

  const top = results[0]
  const lat2 = Number(top.lat)
  const lng2 = Number(top.lon)
  if (!Number.isFinite(lat2) || !Number.isFinite(lng2)) throw new GeocodeError('Invalid lookup result')

  // Keep the label short: the first 2 comma-parts (e.g. "12 High Street, Melton Mowbray").
  const label = top.display_name.split(',').slice(0, 2).join(',').trim() || q
  return { lat: lat2, lng: lng2, label }
}
