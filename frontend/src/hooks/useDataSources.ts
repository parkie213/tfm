import { useEffect, useRef, useState } from 'react'
import type { DataSource } from '../proto/tfm/v1/data_sources_pb'
import { client } from '../transport'

export interface UseDataSourcesResult {
  sources: DataSource[]
  lastUpdated: Date | null
  loading: boolean
  error: string | null
}

const POLL_INTERVAL_MS = 20_000

/**
 * Polls GetDataSources every 20s.
 * Mirrors the pattern in useTownCentreBuses: mounted guard + AbortController via abortRef.
 */
export function useDataSources(): UseDataSourcesResult {
  const [sources, setSources] = useState<DataSource[]>([])
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    let mounted = true
    setLoading(true)

    async function fetchSources() {
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller

      try {
        const res = await client.getDataSources({}, { signal: controller.signal })
        if (!mounted) return
        setSources(res.sources)
        setLastUpdated(new Date())
        setError(null)
      } catch (err) {
        if (!mounted) return
        if (err instanceof Error && err.name === 'AbortError') return
        setError(err instanceof Error ? err.message : 'Failed to load data sources')
      } finally {
        if (mounted) setLoading(false)
      }
    }

    fetchSources()
    const interval = setInterval(fetchSources, POLL_INTERVAL_MS)

    return () => {
      mounted = false
      clearInterval(interval)
      abortRef.current?.abort()
    }
  }, [])

  return { sources, lastUpdated, loading, error }
}
