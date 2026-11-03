// Small planar geometry helpers for animating markers. At a town's scale (a few km) an
// equirectangular approximation is accurate to well under a metre, so we avoid the cost and
// complexity of full spherical maths. Shared by bus animation now; trains later.

export interface Pt {
  lat: number
  lng: number
}

const M_PER_DEG_LAT = 111_320

function metresPerDegLng(lat: number): number {
  return 111_320 * Math.cos((lat * Math.PI) / 180)
}

/** Planar distance in metres between two lat/lng points (accurate at town scale). */
export function metresBetween(a: Pt, b: Pt): number {
  const lat0 = (a.lat + b.lat) / 2
  const dx = (b.lng - a.lng) * metresPerDegLng(lat0)
  const dy = (b.lat - a.lat) * M_PER_DEG_LAT
  return Math.hypot(dx, dy)
}

/** Linear interpolation between two points; t in [0,1]. */
export function lerpPt(a: Pt, b: Pt, t: number): Pt {
  return { lat: a.lat + (b.lat - a.lat) * t, lng: a.lng + (b.lng - a.lng) * t }
}

/** Compass bearing 0-359 from a to b (0 = north, 90 = east). */
export function bearingBetween(a: Pt, b: Pt): number {
  const lat0 = (a.lat + b.lat) / 2
  const dx = (b.lng - a.lng) * metresPerDegLng(lat0)
  const dy = (b.lat - a.lat) * M_PER_DEG_LAT
  const deg = (Math.atan2(dx, dy) * 180) / Math.PI
  return (deg + 360) % 360
}

/** Clamp a number to [lo, hi]. */
export function clamp(v: number, lo: number, hi: number): number {
  return v < lo ? lo : v > hi ? hi : v
}

/** The point `metres` along a compass `bearingDeg` (0 = north, 90 = east) from `p`. Planar. */
export function destPoint(p: Pt, bearingDeg: number, metres: number): Pt {
  const rad = (bearingDeg * Math.PI) / 180
  const north = metres * Math.cos(rad)
  const east = metres * Math.sin(rad)
  return {
    lat: p.lat + north / M_PER_DEG_LAT,
    lng: p.lng + east / metresPerDegLng(p.lat),
  }
}

// ---- Polyline (route) helpers: snap a point onto a route and walk along it ----

/** Cumulative distance (metres) at each vertex; `cum[i]` is the route distance to `shape[i]`. */
export function cumulativeDistances(shape: Pt[]): number[] {
  const cum = new Array<number>(shape.length)
  cum[0] = 0
  for (let i = 1; i < shape.length; i++) {
    cum[i] = cum[i - 1] + metresBetween(shape[i - 1], shape[i])
  }
  return cum
}

export interface Snapped {
  s: number // chainage: distance along the route to the snapped point, in metres
  lat: number
  lng: number
  dist: number // perpendicular distance from the original point to the route, in metres
}

// When a prior chainage is known, only consider route segments within this window of it. This stops
// a fix near a self-overlap (loop / out-and-back street) snapping to a far lobe and teleporting the
// bus along the route. Generous forward allowance covers a missed fix at town speeds.
const SNAP_WINDOW_BACK_M = 150
const SNAP_WINDOW_FWD_M = 800

/**
 * Snaps a point to the nearest point on a polyline. When `priorS` is given, the search is restricted
 * to a chainage window around it (and ties are biased toward it) — this disambiguates routes that
 * pass near themselves, preferring the snap consistent with where the vehicle already was. If nothing
 * falls in the window (a genuine large jump), it retries globally.
 */
export function snapToPolyline(
  pt: Pt,
  shape: Pt[],
  cum: number[],
  priorS: number | null = null,
): Snapped | null {
  if (shape.length < 2) return null
  const mLng = metresPerDegLng(pt.lat)
  // Local planar frame centred on the query point (so the point itself is the origin).
  const toXY = (p: Pt): [number, number] => [
    (p.lng - pt.lng) * mLng,
    (p.lat - pt.lat) * M_PER_DEG_LAT,
  ]

  let best: Snapped | null = null
  let bestCost = Infinity
  for (let i = 0; i < shape.length - 1; i++) {
    // Skip segments outside the chainage window around the prior position.
    if (priorS != null && (cum[i + 1] < priorS - SNAP_WINDOW_BACK_M || cum[i] > priorS + SNAP_WINDOW_FWD_M)) {
      continue
    }
    const [ax, ay] = toXY(shape[i])
    const [bx, by] = toXY(shape[i + 1])
    const dx = bx - ax
    const dy = by - ay
    const segLen2 = dx * dx + dy * dy
    let t = segLen2 > 0 ? -(ax * dx + ay * dy) / segLen2 : 0
    t = t < 0 ? 0 : t > 1 ? 1 : t
    const cx = ax + t * dx
    const cy = ay + t * dy
    const dist = Math.hypot(cx, cy)
    const s = cum[i] + t * (cum[i + 1] - cum[i])
    // Tie-break toward the prior chainage (0.02 m of cost per metre of chainage gap — gentle).
    const cost = priorS == null ? dist : dist + 0.02 * Math.abs(s - priorS)
    if (cost < bestCost) {
      bestCost = cost
      best = { s, dist, lat: pt.lat + cy / M_PER_DEG_LAT, lng: pt.lng + cx / mLng }
    }
  }
  // Nothing in the window (a genuine large jump) → fall back to a global search.
  if (!best && priorS != null) return snapToPolyline(pt, shape, cum, null)
  return best
}

/** The lat/lng at chainage `s` along the route, plus the route's tangent bearing there. */
export function pointAtDistance(
  shape: Pt[],
  cum: number[],
  s: number,
): { lat: number; lng: number; bearing: number } {
  const total = cum[cum.length - 1]
  const sc = clamp(s, 0, total)
  let i = 0
  while (i < cum.length - 2 && cum[i + 1] < sc) i++
  const segLen = cum[i + 1] - cum[i] || 1
  const t = (sc - cum[i]) / segLen
  const a = shape[i]
  const b = shape[i + 1]
  const p = lerpPt(a, b, t)
  return { lat: p.lat, lng: p.lng, bearing: bearingBetween(a, b) }
}
