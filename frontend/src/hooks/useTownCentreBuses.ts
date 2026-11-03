import { useEffect, useState } from 'react'
import type { Departure } from '../proto/tfm/v1/boards_pb'
import type { Stop } from '../proto/tfm/v1/stops_pb'
import { client } from '../transport'
import { useServerStream } from './useServerStream'
import type { GetBusBoardResponse } from '../proto/tfm/v1/boards_pb'

// ---- geo helpers (planar / haversine, street scale) --------------------

export function metresBetween(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const R = 6371000
  const dLat = ((bLat - aLat) * Math.PI) / 180
  const dLng = ((bLng - aLng) * Math.PI) / 180
  const lat1 = (aLat * Math.PI) / 180
  const lat2 = (bLat * Math.PI) / 180
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}

/** Square bbox around a point that comfortably contains a radius (metres). */
function bboxAround(lat: number, lng: number, radiusM: number) {
  const dLat = radiusM / 111_320
  const dLng = radiusM / (111_320 * Math.cos((lat * Math.PI) / 180))
  return { minLat: lat - dLat, maxLat: lat + dLat, minLng: lng - dLng, maxLng: lng + dLng }
}

// A NaPTAN bus stop type (BCT on-street, BCS / BCQ bays / variable). Excludes rail (RSE/RLY/MET).
function isBusStop(s: Stop): boolean {
  return s.stopType.startsWith('BC')
}

// ---- nearby stop discovery --------------------------------------------------

export interface UseNearbyStopsResult {
  stops: Stop[]
  atcoCodes: string[]
  loading: boolean
  error: string | null
}

// Search radius around the point; beyond this a stop isn't "nearby" for a kiosk.
const NEARBY_SEARCH_RADIUS_M = 800
/** Returns nearby bus-stop candidates, nearest first, for an explicit kiosk selection. */
export function useNearbyStops(point: { lat: number; lng: number } | null, radiusM = NEARBY_SEARCH_RADIUS_M): UseNearbyStopsResult {
  const [stops, setStops] = useState<Stop[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const lat = point?.lat
  const lng = point?.lng

  useEffect(() => {
    if (lat === undefined || lng === undefined) {
      setStops([])
      setLoading(false)
      return
    }
    const here = { lat, lng } // narrowed copy for the async closure
    let mounted = true
    const controller = new AbortController()
    const bbox = bboxAround(here.lat, here.lng, radiusM)
    setStops([])
    setError(null)
    setLoading(true)

    async function run() {
      try {
        const res = await client.getStopsInViewport(bbox, { signal: controller.signal })
        if (!mounted) return
        const ranked = res.stops
          .filter(isBusStop)
          .map((s) => ({ s, d: metresBetween(here.lat, here.lng, s.lat, s.lng) }))
          .filter(({ d }) => d <= radiusM)
          .sort((a, b) => a.d - b.d)
        if (res.truncated) throw new Error('The area contains too many stops. Try a more precise location.')
        setStops(ranked.map(({ s }) => s))
        setError(null)
      } catch (err) {
        if (!mounted) return
        if (err instanceof Error && err.name === 'AbortError') return
        setError(err instanceof Error ? err.message : 'Failed to load nearby stops')
      } finally {
        if (mounted) setLoading(false)
      }
    }

    run()
    return () => {
      mounted = false
      controller.abort()
    }
  }, [lat, lng, radiusM])

  const atcoCodes = stops.map((s) => s.atcoCode)
  return { stops, atcoCodes, loading, error }
}

// ---- board for explicitly selected stops ---------------------------------

export interface CombinedDeparture {
  departure: Departure
  /** The selected boarding stop for this departure. */
  stopName: string
  atcoCode: string
}

export interface UseCombinedBusDeparturesResult {
  departures: CombinedDeparture[]
  loading: boolean
  error: string | null
  /** Bus timetables are disabled on the server. */
  unimplemented: boolean
  lastUpdated: Date | null
  /** Upcoming mode only: more services were due than the server response limit. */
  truncated: boolean
  /** Stop queries that failed; the returned rows are a partial board. */
  unavailableAtcoCodes: string[]
}

/** One subscription; Java owns trip deduplication, stop choice, date handling, and ordering. */
export function useCombinedBusDepartures(atcoCodes: string[], date = ''): UseCombinedBusDeparturesResult {
  const [data, setData] = useState<GetBusBoardResponse | null>(null)
  const [loading, setLoading] = useState(atcoCodes.length > 0)
  const key = atcoCodes.join(',')
  const { unavailable, error } = useServerStream<GetBusBoardResponse>({
    enabled: atcoCodes.length > 0,
    deps: [key, date],
    makeStream: (signal) => client.streamBusBoard({ atcoCodes, date }, { signal }),
    onReset: () => {
      setData(null)
      setLoading(true)
    },
    onMessage: (response) => {
      setData(response)
      setLoading(false)
    },
  })
  return {
    departures: atcoCodes.length === 0 ? [] : (data?.departures ?? []).flatMap((row) =>
      row.departure ? [{ departure: row.departure, atcoCode: row.atcoCode, stopName: row.stopName }] : []),
    loading: atcoCodes.length > 0 && loading && !unavailable && !error,
    error,
    unimplemented: unavailable,
    lastUpdated: data ? new Date(data.generatedAt) : null,
    truncated: data?.truncated ?? false,
    unavailableAtcoCodes: data?.unavailableAtcoCodes ?? [],
  }
}
