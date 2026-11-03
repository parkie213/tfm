import { useEffect, useRef, useState } from 'react'
import { ConnectError } from '@connectrpc/connect'
import type { GetStationPlatformsResponse } from '../proto/tfm/v1/trains_pb'
import { client } from '../transport'

const POLL_INTERVAL_MS = 10_000

export interface UseStationPlatformsResult {
  data: GetStationPlatformsResponse | null
  loading: boolean
  error: string | null
}

/**
 * Polls `getStationPlatforms` for the given CRS every 10 seconds while mounted.
 * Shows a loading skeleton on first fetch; keeps stale data visible on subsequent polls.
 * Pass undefined to stay idle (clears any previous data).
 */
export function useStationPlatforms(crs: string | undefined): UseStationPlatformsResult {
  const [data, setData] = useState<GetStationPlatformsResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Stable ref — the interval callback reads from this to avoid re-registering every render.
  const crsRef = useRef(crs)
  crsRef.current = crs

  useEffect(() => {
    if (!crs) {
      setData(null)
      setLoading(false)
      setError(null)
      return
    }

    let cancelled = false
    // Track whether we've received at least one response so we can show stale-while-revalidate.
    let hasData = false

    async function doFetch() {
      const currentCrs = crsRef.current
      if (!currentCrs || cancelled) return

      if (!hasData) {
        // First fetch: show a skeleton.
        setLoading(true)
      }
      // Subsequent fetches: keep showing old data silently (no flicker).

      try {
        const resp = await client.getStationPlatforms({ crs: currentCrs })
        if (cancelled) return
        hasData = true
        setData(resp)
        setError(null)
        setLoading(false)
      } catch (err) {
        if (cancelled) return
        if (err instanceof Error && err.name === 'AbortError') return
        const msg =
          err instanceof ConnectError
            ? err.message
            : err instanceof Error
              ? err.message
              : 'Failed to load platform board'
        setError(msg)
        setLoading(false)
      }
    }

    // Reset and fire immediately.
    setData(null)
    setLoading(true)
    setError(null)
    void doFetch()

    const timer = setInterval(() => { void doFetch() }, POLL_INTERVAL_MS)

    return () => {
      cancelled = true
      clearInterval(timer)
    }
  // Re-run when the CRS changes (string comparison — safe).
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [crs])

  return { data, loading, error }
}
