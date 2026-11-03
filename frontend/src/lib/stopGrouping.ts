import type { Stop } from '../proto/tfm/v1/stops_pb'
import { metresBetween } from './route'

// Bus stops sharing a name + locality and sitting within this radius of their centroid collapse into a
// single "place" marker (e.g. a bus station's stands S1–S4, or an opposite/adjacent pair). The radius
// keeps two genuinely-distinct stops that happen to share a name (different areas) from merging.
export const GROUP_RADIUS_M = 200

/**
 * The short bay/stand code from a NaPTAN indicator. The data carries values like "Stand S1" (a bus
 * station bay) or "opp"/"outside" (a roadside relation); we drop a leading "Stand " so a bay reads as
 * "S1" while a relation indicator passes through unchanged.
 */
export function standCode(indicator: string): string {
  return indicator.replace(/^stand\s+/i, '').trim()
}

/** True when a stand code looks like a numbered bay (S1, W4, A, 3) rather than a relation (opp, outside). */
export function isBayCode(code: string): boolean {
  return /^[a-z]?\d/i.test(code)
}

// Natural order so S2 sorts before S10 (a plain string sort puts S10 between S1 and S2).
const standCollator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })

/**
 * One marker label for a cluster's stands: codes normalised ("Stand S1" → "S1"), de-duplicated,
 * natural-sorted (S1·S2·S3, not S3·S4·S2·S1), and capped at {@link max} with a "+N" overflow so a big
 * bus station doesn't produce an unreadably long label. Returns '' when no stand has a code.
 */
export function formatStandLabel(indicators: string[], max = 4): string {
  const codes = [...new Set(indicators.map(standCode).filter(Boolean))].sort((a, b) =>
    standCollator.compare(a, b),
  )
  if (codes.length <= max) return codes.join('·')
  return [...codes.slice(0, max), `+${codes.length - max}`].join('·')
}

/** A set of co-located, same-named bus stops collapsed to one marker at their centroid. */
export interface StopGroup {
  key: string
  name: string
  members: Stop[]
  lat: number
  lng: number
}

/**
 * Partitions bus stops into tight same-name groups (≥2 members within {@link GROUP_RADIUS_M} of their
 * centroid) and ungrouped singles. The group key is locality + name, so "St Mary's Way" Stand S1–S4
 * collapse into one marker while a lone roadside stop is left exactly as before.
 *
 * Shared by the main map (ViewportStops) and the TV/kiosk map (ShopMap) so the two stay in lock-step.
 */
export function groupBusStops(busStops: Stop[]): { groups: StopGroup[]; singles: Stop[] } {
  const byKey = new Map<string, Stop[]>()
  for (const s of busStops) {
    const key = `${s.locality.toLowerCase().trim()}|${s.name.toLowerCase().trim()}`
    const arr = byKey.get(key)
    if (arr) arr.push(s)
    else byKey.set(key, [s])
  }
  const groups: StopGroup[] = []
  const singles: Stop[] = []
  for (const [key, members] of byKey) {
    if (members.length < 2) {
      singles.push(...members)
      continue
    }
    const lat = members.reduce((acc, s) => acc + s.lat, 0) / members.length
    const lng = members.reduce((acc, s) => acc + s.lng, 0) / members.length
    const tight = members.every((s) => metresBetween({ lat, lng }, { lat: s.lat, lng: s.lng }) <= GROUP_RADIUS_M)
    if (tight) {
      groups.push({ key, name: members[0].name, members, lat, lng })
    } else {
      singles.push(...members) // same name, different places — keep them separate
    }
  }
  return { groups, singles }
}
