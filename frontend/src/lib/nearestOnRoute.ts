/**
 * Nearest point on a set of polylines to a given lat/lng, with the great-circle distance in metres.
 * Used by the diagnostic GPS-fix overlay to show, per fix, how far it is from the drawn route and where
 * it projects onto it — independent of the server's (timing-dependent) snap flag.
 *
 * Projection uses a local equirectangular approximation (longitude scaled by cos(lat)); fine at the
 * per-segment scale of a bus route. Distance is haversine for accuracy.
 */

const R = 6_371_000 // Earth radius, metres

function haversineM(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const rad = Math.PI / 180
  const dLat = (bLat - aLat) * rad
  const dLng = (bLng - aLng) * rad
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(aLat * rad) * Math.cos(bLat * rad) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)))
}

export interface NearestResult {
  distM: number
  lat: number
  lng: number
}

/**
 * @param lines polylines as arrays of [lat, lng]. Returns the nearest projected point across all of
 *   them, or distM=Infinity (and the input point) when there's no geometry.
 */
export function nearestOnRoute(lat: number, lng: number, lines: [number, number][][]): NearestResult {
  let best: NearestResult = { distM: Infinity, lat, lng }
  const k = Math.cos(lat * (Math.PI / 180)) // scale lng→x so x/y are locally isotropic
  const px = lng * k
  const py = lat
  for (const line of lines) {
    for (let i = 0; i + 1 < line.length; i++) {
      const [aLat, aLng] = line[i]
      const [bLat, bLng] = line[i + 1]
      const ax = aLng * k
      const ay = aLat
      const dx = bLng * k - ax
      const dy = bLat - ay
      const len2 = dx * dx + dy * dy
      let t = len2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0
      t = Math.max(0, Math.min(1, t))
      const nLat = aLat + t * (bLat - aLat)
      const nLng = aLng + t * (bLng - aLng)
      const d = haversineM(lat, lng, nLat, nLng)
      if (d < best.distM) best = { distM: d, lat: nLat, lng: nLng }
    }
  }
  return best
}
