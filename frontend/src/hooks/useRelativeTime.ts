import { useState, useEffect } from 'react'

/**
 * Current wall-clock time as a Date, ticking on the minute (aligned, like
 * useLiveTime). Relative "in N min" labels only change at minute boundaries,
 * so a faster tick would just burn re-renders.
 */
export function useRelativeTime(): Date {
  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    let interval: ReturnType<typeof setInterval> | undefined
    const msToNextMinute = 60_000 - (Date.now() % 60_000)
    const timeout = setTimeout(() => {
      setNow(new Date())
      interval = setInterval(() => setNow(new Date()), 60_000)
    }, msToNextMinute)
    return () => {
      clearTimeout(timeout)
      if (interval) clearInterval(interval)
    }
  }, [])

  return now
}

const HHMM = /^(\d{1,2}):(\d{2})$/

export interface RelativeTime {
  label: string // "due" | "in 1 min" | "in 12 min"
  minutes: number
}

/**
 * Minutes-until label for a board time.
 *
 * `timeStr` is a local "HH:mm" string (scheduled or expected). Returns null
 * when it isn't a plain time (e.g. "On time", "Cancelled"), is in the past,
 * or is more than 20 minutes away — the absolute time reads better there.
 * Times that appear >6h in the past are treated as tomorrow's (overnight
 * boards around midnight).
 */
export function relativeTime(timeStr: string, now: Date): RelativeTime | null {
  const match = HHMM.exec(timeStr.trim())
  if (!match) return null

  const target = new Date(now)
  target.setHours(Number(match[1]), Number(match[2]), 0, 0)
  if (target.getTime() < now.getTime() - 6 * 3_600_000) {
    target.setDate(target.getDate() + 1)
  }

  const minutes = Math.round((target.getTime() - now.getTime()) / 60_000)
  if (minutes < 0 || minutes > 20) return null
  return { label: minutes === 0 ? 'due' : `in ${minutes} min`, minutes }
}
