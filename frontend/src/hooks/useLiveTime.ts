import { useState, useEffect } from 'react'

function formatNow(): string {
  return new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
}

/** Current wall-clock time as HH:MM, ticking on the minute. */
export function useLiveTime(): string {
  const [time, setTime] = useState(formatNow)

  useEffect(() => {
    // Align the first tick to the top of the next minute, then tick every minute.
    let interval: ReturnType<typeof setInterval> | undefined
    const msToNextMinute = 60_000 - (Date.now() % 60_000)
    const timeout = setTimeout(() => {
      setTime(formatNow())
      interval = setInterval(() => setTime(formatNow()), 60_000)
    }, msToNextMinute)
    return () => {
      clearTimeout(timeout)
      if (interval) clearInterval(interval)
    }
  }, [])

  return time
}
