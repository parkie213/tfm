/**
 * Human label for a signed delay in minutes from the live train estimator.
 *
 * Single source of truth shared by the live-train map markers and the platform board, so the same
 * train never shows a different status in the two places. `0` means "no delay reported" (the TRUST
 * proto uses 0 as its default), so it's surfaced as unknown rather than a confident "on time"; ±1 is
 * treated as on time; the `~` prefix flags that the figure is an estimate, not a confirmed time.
 */
export function punctuality(delayMinutes: number): string {
  if (delayMinutes >= 2) return `~${delayMinutes} min late`
  if (delayMinutes <= -2) return `~${-delayMinutes} min early`
  if (delayMinutes === 0) return 'delay unknown'
  return 'on time'
}
