import { useEffect, useMemo, useRef, useState } from 'react'
import { ConnectError, Code } from '@connectrpc/connect'
import type { GetTrainDeparturesResponse, GetTrainArrivalsResponse, GetBusDeparturesResponse, Departure, Coach } from '../proto/tfm/v1/boards_pb'
import type { GetServiceStopsResponse } from '../proto/tfm/v1/routes_pb'
import { client } from '../transport'
import { MELTON_MOWBRAY_CRS } from '../constants/stops'
import { useServerStream } from './useServerStream'

export interface UseDeparturesResult {
  data: GetTrainDeparturesResponse | null
  error: string | null
  loading: boolean
  lastUpdated: Date | null
}

export interface UseArrivalsResult {
  data: GetTrainArrivalsResponse | null
  error: string | null
  /** true when the server has not yet implemented arrivals (gRPC UNIMPLEMENTED) */
  unimplemented: boolean
  loading: boolean
  lastUpdated: Date | null
}

export function useTrainDepartures(crs: string = MELTON_MOWBRAY_CRS): UseDeparturesResult {
  const [data, setData] = useState<GetTrainDeparturesResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const { error: streamError, unavailable } = useServerStream<GetTrainDeparturesResponse>({
    enabled: true,
    deps: [crs],
    makeStream: (signal) => client.streamTrainDepartures({ crs, numRows: 15 }, { signal }),
    onReset: () => setLoading(true),
    onMessage: (resp) => {
      setData(resp)
      setLoading(false)
      setLastUpdated(new Date())
    },
  })

  const error = unavailable ? 'Train departures are not configured.' : streamError

  // A sustained failure (bad CRS, backend down) replaces the eternal skeleton with the error: stop
  // "loading" once an error is surfaced so the board can show it. Cleared the moment a frame arrives.
  return { data, error, loading: loading && !error, lastUpdated }
}

export function useTrainArrivals(crs: string = MELTON_MOWBRAY_CRS): UseArrivalsResult {
  const [data, setData] = useState<GetTrainArrivalsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const { unavailable } = useServerStream<GetTrainArrivalsResponse>({
    enabled: true,
    deps: [crs],
    makeStream: (signal) => client.streamTrainArrivals({ crs, numRows: 15 }, { signal }),
    onReset: () => setLoading(true),
    onMessage: (resp) => {
      setData(resp)
      setLoading(false)
      setLastUpdated(new Date())
    },
  })

  // UNIMPLEMENTED (arrivals product not configured) → stop showing a skeleton; the station board
  // degrades to departures-only.
  return { data, error: null, unimplemented: unavailable, loading: loading && !unavailable, lastUpdated }
}

// ----- Unified station board (departures + arrivals merged per service) -----

/** One service at the station: a single row carrying its arrival and/or departure side. */
export interface StationBoardRow {
  serviceId: string
  origin: string // where the service started ("" if it originates here)
  destination: string // where it ends ("" if it terminates here)
  arr: { scheduled: string; expected: string; status: string } | null // null = doesn't arrive here (originates)
  dep: { scheduled: string; expected: string; status: string } | null // null = doesn't depart here (terminates)
  platform: string
  operator: string
  isLive: boolean
  coachCount: number
  coaches: Coach[]
  sortSecs: number
}

export interface UseStationBoardResult {
  rows: StationBoardRow[]
  stationName: string
  nrccMessages: string
  loading: boolean
  error: string | null
}

// "HH:MM" → seconds since midnight (unparseable times sort to the bottom). The station board orders by
// this on the *scheduled* time so a delayed service keeps its booked slot. Exported so the TV board can
// order identically.
export function hhmmSecs(hhmm: string): number {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm)
  return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 : Number.MAX_SAFE_INTEGER
}

/** Joins a service's departure and arrival sides (keyed on serviceId). */
function rowKey(d: Departure): string {
  return d.serviceId || `${d.scheduledTime}|${d.destination || d.origin}`
}

/**
 * Merges the separate train departures + arrivals boards into one row per service. At a through
 * station a service has both an arrival and a departure (shown side by side); one that terminates
 * here shows arrival-only, one that originates here departure-only. Resilient to arrivals being
 * unimplemented (code 12) — degrades to a departures-only board.
 */
export function useStationBoard(crs: string = MELTON_MOWBRAY_CRS): UseStationBoardResult {
  const departures = useTrainDepartures(crs)
  const arrivals = useTrainArrivals(crs)

  const rows = useMemo<StationBoardRow[]>(() => {
    const byKey = new Map<string, StationBoardRow>()

    for (const d of departures.data?.departures ?? []) {
      const k = rowKey(d)
      byKey.set(k, {
        serviceId: d.serviceId,
        origin: d.origin,
        destination: d.destination,
        arr: null,
        dep: { scheduled: d.scheduledTime, expected: d.expectedTime, status: d.status },
        platform: d.platformOrBay,
        operator: d.operator,
        isLive: d.isLive,
        coachCount: d.coachCount,
        coaches: d.coaches,
        sortSecs: hhmmSecs(d.scheduledTime),
      })
    }

    for (const a of arrivals.data?.arrivals ?? []) {
      const k = rowKey(a)
      const arr = { scheduled: a.scheduledTime, expected: a.expectedTime, status: a.status }
      const existing = byKey.get(k)
      if (existing) {
        existing.arr = arr
        if (!existing.origin) existing.origin = a.origin
        existing.platform = existing.platform || a.platformOrBay
        existing.operator = existing.operator || a.operator
        existing.isLive = existing.isLive || a.isLive
        if (existing.coaches.length === 0 && a.coaches.length > 0) existing.coaches = a.coaches
        if (existing.coachCount === 0) existing.coachCount = a.coachCount
      } else {
        byKey.set(k, {
          serviceId: a.serviceId,
          origin: a.origin,
          destination: a.destination, // empty for a terminating service
          arr,
          dep: null,
          platform: a.platformOrBay,
          operator: a.operator,
          isLive: a.isLive,
          coachCount: a.coachCount,
          coaches: a.coaches,
          sortSecs: hhmmSecs(a.scheduledTime),
        })
      }
    }

    return [...byKey.values()].sort((x, y) => x.sortSecs - y.sortSecs)
  }, [departures.data, arrivals.data])

  return {
    rows,
    stationName: departures.data?.stationName ?? arrivals.data?.stationName ?? '',
    nrccMessages: departures.data?.nrccMessages ?? '',
    loading: departures.loading,
    error: departures.error,
  }
}

export interface UseBusDeparturesResult {
  data: GetBusDeparturesResponse | null
  error: string | null
  /** true when the server has not yet implemented bus departures (gRPC UNIMPLEMENTED) */
  unimplemented: boolean
  loading: boolean
  lastUpdated: Date | null
}

/**
 * Subscribes to the streamed bus-departures board for a single stop (the server keeps it fresh and
 * pushes changes — incl. the live punctuality overlay). Pass null to stay idle (e.g. when the train
 * station is selected). Re-subscribes from scratch whenever atcoCode or date changes.
 */
export function useBusDepartures(atcoCode: string | null, date: string = ''): UseBusDeparturesResult {
  const [data, setData] = useState<GetBusDeparturesResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const hasStop = !!atcoCode
  const atco = atcoCode ?? ''

  // Clear when deselected so stale data doesn't flash on the next selection.
  useEffect(() => {
    if (!hasStop) {
      setData(null)
      setLoading(false)
      setLastUpdated(null)
    }
  }, [hasStop])

  const { unavailable, error } = useServerStream<GetBusDeparturesResponse>({
    enabled: hasStop,
    deps: [atco, date],
    makeStream: (signal) =>
      client.streamBusDepartures(
        // Upcoming (date=''): next 12 buses whenever they are. Whole-day (date set): all rows.
        date
          ? { atcoCode: atco, numRows: 0, lookaheadMins: 0, date }
          : { atcoCode: atco, numRows: 12, lookaheadMins: 0, date: '' },
        { signal },
      ),
    onReset: () => {
      setData(null)
      setLoading(true)
      setLastUpdated(null)
    },
    onMessage: (resp) => {
      setData(resp)
      setLoading(false)
      setLastUpdated(new Date())
    },
  })

  return {
    data,
    error,
    unimplemented: unavailable,
    loading: hasStop && loading && !unavailable && !error,
    lastUpdated,
  }
}

export interface UseServiceStopsResult {
  data: GetServiceStopsResponse | null
  error: string | null
  /** true when the server has not yet implemented service stops (gRPC UNIMPLEMENTED) */
  unimplemented: boolean
  loading: boolean
}

/**
 * Shared core for the calling-points hooks. Re-fetches whenever {@code key} changes (a null key stays
 * idle); the {@code fetcher} is read from a ref so a fresh closure each render doesn't re-fire. No
 * polling — timetables don't change mid-session.
 */
function useStopsFetch(
  key: string | null,
  fetcher: (signal: AbortSignal) => Promise<GetServiceStopsResponse>,
): UseServiceStopsResult {
  const [data, setData] = useState<GetServiceStopsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [unimplemented, setUnimplemented] = useState(false)
  const [loading, setLoading] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  useEffect(() => {
    if (!key) {
      setData(null)
      setError(null)
      setUnimplemented(false)
      setLoading(false)
      return
    }

    let mounted = true
    setLoading(true)
    setData(null)
    setError(null)
    setUnimplemented(false)

    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    async function fetchStops() {
      try {
        const response = await fetcherRef.current(controller.signal)
        if (!mounted) return
        setData(response)
        setError(null)
        setUnimplemented(false)
      } catch (err) {
        if (!mounted) return
        if (err instanceof Error && err.name === 'AbortError') return
        if (err instanceof ConnectError && err.code === Code.Unimplemented) {
          setUnimplemented(true)
          setError(null)
          return
        }
        setError(err instanceof Error ? err.message : 'Failed to load service stops')
      } finally {
        if (mounted) setLoading(false)
      }
    }

    fetchStops()

    return () => {
      mounted = false
      abortRef.current?.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  return { data, error, unimplemented, loading }
}

/**
 * Fetches the full calling-stop list for a single GTFS trip. Pass null to stay idle.
 */
export function useServiceStops(tripId: string | null): UseServiceStopsResult {
  return useStopsFetch(tripId, (signal) => client.getServiceStops({ tripId: tripId! }, { signal }))
}

/**
 * Fetches the calling-stop list for a whole route (picked by clicking its line on the map). The
 * server resolves a representative trip — the fullest variant — and returns its ordered stops. Pass a
 * null/empty short name to stay idle. {@code longName} disambiguates a reused route number.
 */
export function useRouteStops(
  shortName: string | null,
  longName: string,
  operator: string,
): UseServiceStopsResult {
  const key = shortName ? `${shortName} ${longName}` : null
  return useStopsFetch(key, (signal) =>
    client.getRouteStops({ routeShortName: shortName!, routeLongName: longName, operator }, { signal }),
  )
}
