import { useState, useEffect } from 'react'

/**
 * Returns Date.now() updating every `ms` milliseconds (default 1 000).
 * Used by DataSourcesView so freshness labels tick live at second resolution.
 */
export function useNowTick(ms = 1_000): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms)
    return () => clearInterval(id)
  }, [ms])

  return now
}
