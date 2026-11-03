import { useEffect, useMemo, useRef, useState } from 'react'
import { ConnectError, Code } from '@connectrpc/connect'
import type { Departure } from '../proto/tfm/v1/boards_pb'
import type { Stop } from '../proto/tfm/v1/stops_pb'
import { client } from '../transport'

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
const NEARBY_SEARCH_RADIUS_M = 400
// Keep stops within this distance of the nearest one: picks up the opposite-direction stop across the
// road (typically 10–60 m apart) without pulling in the next cluster down the street.
const NEARBY_CLUSTER_SLACK_M = 75

/**
 * Finds the bus stops at a point — the nearest active bus stop plus any others within
 * NEARBY_CLUSTER_SLACK_M of that distance (so both directions of an on-street pair). Fetched once per
 * point (stops don't move); the TV view feeds the resulting ATCO codes into useCombinedBusDepartures
 * to produce a single board for the cluster. A null point yields no stops.
 */
export function useNearbyStops(point: { lat: number; lng: number } | null): UseNearbyStopsResult {
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
    const bbox = bboxAround(here.lat, here.lng, NEARBY_SEARCH_RADIUS_M)
    setLoading(true)

    async function run() {
      try {
        const res = await client.getStopsInViewport(bbox, { signal: controller.signal })
        if (!mounted) return
        const ranked = res.stops
          .filter(isBusStop)
          .map((s) => ({ s, d: metresBetween(here.lat, here.lng, s.lat, s.lng) }))
          .filter(({ d }) => d <= NEARBY_SEARCH_RADIUS_M)
          .sort((a, b) => a.d - b.d)
        const cutoff = ranked.length ? ranked[0].d + NEARBY_CLUSTER_SLACK_M : 0
        setStops(ranked.filter(({ d }) => d <= cutoff).map(({ s }) => s))
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
  }, [lat, lng])

  const atcoCodes = useMemo(() => stops.map((s) => s.atcoCode), [stops])
  return { stops, atcoCodes, loading, error }
}

// ---- combined departures across the cluster ---------------------------------

export interface CombinedDeparture {
  departure: Departure
  /** The nearby stop this service departs from (its stand within the cluster). */
  stopName: string
  atcoCode: string
}

export interface UseCombinedBusDeparturesResult {
  departures: CombinedDeparture[]
  loading: boolean
  error: string | null
  /** All contributing stops returned UNIMPLEMENTED (bus timetables not enabled on the server). */
  unimplemented: boolean
  lastUpdated: Date | null
  /** Upcoming mode only: more than DISPLAY_LIMIT services were due — the list was capped. */
  truncated: boolean
}

const POLL_INTERVAL_MS = 30_000
const PER_STOP_ROWS = 25
const DISPLAY_LIMIT = 14
// Keep a service on the board for this long after its due time before dropping it as departed.
const PAST_GRACE_SECS = 60

function hhmmSecs(hhmm: string): number {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm)
  return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 : Number.MAX_SAFE_INTEGER
}

// Sort/dedup key: a single GTFS trip (serviceId) is one service even if it calls at several
// stops in the cluster. Fall back to line+destination+time when serviceId is blank.
function tripKey(d: Departure): string {
  return d.serviceId || `${d.line}|${d.destination}|${d.scheduledTime}`
}

// Time used for ordering AND for the "already gone" filter. It must match the time shown as the row's
// PRIMARY (big) number, or the board reorders out of sync with what's displayed: the big number is the
// scheduled time, switching to the expected time only once the delay is large enough that the row
// actually shows it (struck scheduled + bold expected, |delay| >= 2 — see TVView `expectedDiffers`).
// So a 1-min-late service still reads "08:15" and stays in the 08:15 slot (instead of silently sorting
// into 08:16 below an on-time 08:16), while a genuinely late service slides down to its shown expected
// slot. Kept in sync with the TVView display threshold.
function sortSecs(d: Departure): number {
  if (d.isLive && Math.abs(d.delayMinutes) >= 2 && /^\d{1,2}:\d{2}$/.test(d.expectedTime)) {
    return hhmmSecs(d.expectedTime)
  }
  return hhmmSecs(d.scheduledTime)
}

/**
 * Treats a set of nearby stops as one logical stop: fetches each stop's departures in parallel and
 * merges them into a single board. A service passing through several stands appears once, at the
 * earliest stand it serves. Polls every 30s; resilient to individual stops failing.
 *
 * `date`: '' = upcoming-from-now (past dropped, capped to DISPLAY_LIMIT). A 'yyyy-MM-dd' shows that
 * whole day's timetable (no past filter, uncapped) — used by the board's day-stepper and "show earlier".
 */
export function useCombinedBusDepartures(
  atcoCodes: string[],
  date: string = '',
): UseCombinedBusDeparturesResult {
  const [departures, setDepartures] = useState<CombinedDeparture[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [unimplemented, setUnimplemented] = useState(false)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)
  const [truncated, setTruncated] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  // Stable identity for the stop set + day so the effect doesn't re-run on every render.
  const key = useMemo(() => [...atcoCodes].sort().join(',') + '|' + date, [atcoCodes, date])

  useEffect(() => {
    if (atcoCodes.length === 0) {
      setDepartures([])
      setLoading(false)
      return
    }

    let mounted = true
    setLoading(true)

    async function fetchAll() {
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller

      const results = await Promise.allSettled(
        atcoCodes.map((atcoCode) =>
          client
            .getBusDepartures(
              // Whole-day mode (date set) asks for the full timetable (numRows 0 = server default);
              // upcoming mode caps each stop so the merge isn't dominated by one busy stand.
              { atcoCode, numRows: date ? 0 : PER_STOP_ROWS, lookaheadMins: 0, date },
              { signal: controller.signal },
            )
            .then((res) => ({ atcoCode, res })),
        ),
      )
      if (!mounted) return

      let anyOk = false
      let allUnimplemented = true
      // Keep one row per trip — the earliest-departing stand it serves.
      const byTrip = new Map<string, CombinedDeparture>()

      for (const r of results) {
        if (r.status !== 'fulfilled') {
          const reason = r.reason
          if (reason instanceof Error && reason.name === 'AbortError') return
          if (!(reason instanceof ConnectError && reason.code === Code.Unimplemented)) {
            allUnimplemented = false
          }
          continue
        }
        anyOk = true
        allUnimplemented = false
        const { atcoCode, res } = r.value
        for (const dep of res.departures) {
          const k = tripKey(dep)
          const existing = byTrip.get(k)
          if (!existing || sortSecs(dep) < sortSecs(existing.departure)) {
            byTrip.set(k, { departure: dep, stopName: res.stopName, atcoCode })
          }
        }
      }

      const sorted = [...byTrip.values()].sort((a, b) => sortSecs(a.departure) - sortSecs(b.departure))
      let merged: CombinedDeparture[]
      let didTruncate = false
      if (date) {
        // Whole-day timetable (a stepped day, or "show earlier" today): show everything, no past filter.
        merged = sorted
      } else {
        // Upcoming: drop departures that have already gone. Times are HH:MM seconds-of-day, so a board
        // left up overnight would wrongly treat early-morning times as "past" — match the >6h-past →
        // tomorrow wrap the relative-time label uses, and keep a 60s grace so a just-due one doesn't vanish.
        const now = new Date()
        const nowSecs = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds()
        const upcoming = sorted.filter((c) => {
          let delta = sortSecs(c.departure) - nowSecs
          if (delta < -6 * 3600) delta += 24 * 3600 // wrapped past midnight → tomorrow
          return delta >= -PAST_GRACE_SECS
        })
        didTruncate = upcoming.length > DISPLAY_LIMIT
        merged = upcoming.slice(0, DISPLAY_LIMIT)
      }

      if (!anyOk && allUnimplemented) {
        setUnimplemented(true)
        setError(null)
      } else if (!anyOk) {
        setError('Failed to load bus departures')
      } else {
        setDepartures(merged)
        setTruncated(didTruncate)
        setUnimplemented(false)
        setError(null)
        setLastUpdated(new Date())
      }
      setLoading(false)
    }

    fetchAll()
    const interval = setInterval(fetchAll, POLL_INTERVAL_MS)
    return () => {
      mounted = false
      clearInterval(interval)
      abortRef.current?.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  return { departures, loading, error, unimplemented, lastUpdated, truncated }
}
