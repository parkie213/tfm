/**
 * Pure freshness helpers — no React, no side-effects.
 * Used by DataSourcesView to compute relative labels and colour tones.
 */

/**
 * Human-readable "time ago" with second-level resolution for recent updates.
 * fromMs is an epoch-ms timestamp; now is Date.now().
 */
export function formatAgo(fromMs: number, now: number): string {
  const diffMs = now - fromMs
  if (diffMs < 10_000) return 'just now'
  if (diffMs < 60_000) return `${Math.floor(diffMs / 1000)}s ago`
  const mins = Math.floor(diffMs / 60_000)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}

export type FreshnessTone = 'green' | 'amber' | 'red' | 'grey'

export interface FreshnessResult {
  tone: FreshnessTone
  /** Short relative label, e.g. "8s ago", "5m ago", or "—" for static/dormant. */
  relLabel: string
  /** Absolute local datetime string for a title= tooltip. */
  absTitle: string
}

/**
 * Compute the colour tone and label for a data source's freshness.
 *
 * Rules:
 *  - expectedIntervalSecs === 0 OR lastUpdatedRfc3339 is empty → grey, "—" (static/baked)
 *  - age <= expectedIntervalSecs → green
 *  - age <= 3 × expectedIntervalSecs → amber
 *  - beyond that → red
 */
export function freshness(
  lastUpdatedRfc3339: string,
  expectedIntervalSecs: number,
  nowMs: number,
): FreshnessResult {
  if (!lastUpdatedRfc3339 || expectedIntervalSecs === 0) {
    return {
      tone: 'grey',
      relLabel: '—',
      absTitle: lastUpdatedRfc3339
        ? new Date(lastUpdatedRfc3339).toLocaleString()
        : 'No update timestamp',
    }
  }

  const updatedMs = new Date(lastUpdatedRfc3339).getTime()
  if (Number.isNaN(updatedMs)) {
    return { tone: 'grey', relLabel: '—', absTitle: 'Invalid timestamp' }
  }

  const ageSecs = (nowMs - updatedMs) / 1000
  const absTitle = new Date(updatedMs).toLocaleString()
  const relLabel = formatAgo(updatedMs, nowMs)

  let tone: FreshnessTone
  if (ageSecs <= expectedIntervalSecs) {
    tone = 'green'
  } else if (ageSecs <= expectedIntervalSecs * 3) {
    tone = 'amber'
  } else {
    tone = 'red'
  }

  return { tone, relLabel, absTitle }
}
