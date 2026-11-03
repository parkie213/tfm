import { useEffect, useRef, useState } from 'react'
import { ConnectError, Code } from '@connectrpc/connect'
import type { GetTrainDeparturesResponse, GetBusDeparturesResponse, GetStationBoardResponse, StationBoardRow } from '../proto/tfm/v1/boards_pb'
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

// ----- Unified station board (departures + arrivals merged per service) -----

export type { StationBoardRow } from '../proto/tfm/v1/boards_pb'

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

/** Subscribes to the combined station board assembled by the Java backend. */
export function useStationBoard(crs: string = MELTON_MOWBRAY_CRS): UseStationBoardResult {
  const [data, setData] = useState<GetStationBoardResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const { error: streamError, unavailable } = useServerStream<GetStationBoardResponse>({
    enabled: true,
    deps: [crs],
    makeStream: (signal) => client.streamStationBoard({ crs, numRows: 15 }, { signal }),
    onReset: () => {
      setData(null)
      setLoading(true)
    },
    onMessage: (response) => {
      setData(response)
      setLoading(false)
    },
  })
  const error = unavailable ? 'Train departures are not configured.' : streamError
  return {
    rows: data?.rows ?? [],
    stationName: data?.stationName ?? '',
    nrccMessages: data?.nrccMessages ?? '',
    loading: loading && !error,
    error,
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
