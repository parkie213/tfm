import { useEffect, useRef, useState } from 'react'
import { ConnectError, Code } from '@connectrpc/connect'
import type { GetTrainServiceResponse } from '../proto/tfm/v1/trains_pb'
import { client } from '../transport'

export interface SelectedTrainService {
  crs: string
  scheduledTime: string
  destination: string
  isArrival: boolean
}

export interface UseTrainServiceResult {
  data: GetTrainServiceResponse | null
  error: string | null
  /** true when the server hasn't implemented GetTrainService yet (gRPC UNIMPLEMENTED) */
  unimplemented: boolean
  loading: boolean
}

/**
 * Fetches the full calling-point list (including lat/lng) for a single train journey identified
 * from a station-board row. The key is (crs, scheduledTime, destination, isArrival) — this is a
 * one-shot fetch (no polling: timetables don't change mid-session). Passing null stays idle.
 */
export function useTrainService(key: SelectedTrainService | null): UseTrainServiceResult {
  const [data, setData] = useState<GetTrainServiceResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [unimplemented, setUnimplemented] = useState(false)
  const [loading, setLoading] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  // Stable string key so we only re-fire when the identity actually changes.
  const stableKey = key
    ? `${key.crs}|${key.scheduledTime}|${key.destination}|${key.isArrival ? '1' : '0'}`
    : null

  useEffect(() => {
    if (!stableKey || !key) {
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

    async function fetch() {
      try {
        const response = await client.getTrainService(
          {
            crs: key!.crs,
            scheduledTime: key!.scheduledTime,
            destination: key!.destination,
            isArrival: key!.isArrival,
          },
          { signal: controller.signal },
        )
        if (!mounted) return
        setData(response)
      } catch (err) {
        if (!mounted) return
        if (err instanceof Error && err.name === 'AbortError') return
        if (err instanceof ConnectError && err.code === Code.Unimplemented) {
          setUnimplemented(true)
          return
        }
        setError(err instanceof Error ? err.message : 'Failed to load train service')
      } finally {
        if (mounted) setLoading(false)
      }
    }

    void fetch()

    return () => {
      mounted = false
      abortRef.current?.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stableKey])

  return { data, error, unimplemented, loading }
}
