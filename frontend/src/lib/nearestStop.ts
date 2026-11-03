import { client } from '../transport'
import { metresBetween } from '../hooks/useTownCentreBuses'
import type { Stop } from '../proto/tfm/v1/stops_pb'
import type { SelectedStop } from '../App'

// How far around the user to look for the nearest stop/station before giving up.
const NEAREST_RADIUS_M = 800

export interface NearbyStop {
  stop: Stop
  distanceM: number
}

/** The closest stops/stations to a point, nearest first (within radius, capped at `limit`). */
export async function findNearbyStops(
  lat: number,
  lng: number,
  { radiusM = NEAREST_RADIUS_M, limit = 8 }: { radiusM?: number; limit?: number } = {},
): Promise<NearbyStop[]> {
  const dLat = radiusM / 111_320
  const dLng = radiusM / (111_320 * Math.cos((lat * Math.PI) / 180))
  const res = await client.getStopsInViewport({
    minLat: lat - dLat,
    minLng: lng - dLng,
    maxLat: lat + dLat,
    maxLng: lng + dLng,
  })
  return res.stops
    .map((stop) => ({ stop, distanceM: metresBetween(lat, lng, stop.lat, stop.lng) }))
    .filter((s) => s.distanceM <= radiusM)
    .sort((a, b) => a.distanceM - b.distanceM)
    .slice(0, limit)
}

/** Search the area around a point for the closest stop or station, mapped to a SelectedStop. */
export async function findNearestStop(lat: number, lng: number): Promise<SelectedStop | null> {
  const dLat = NEAREST_RADIUS_M / 111_320
  const dLng = NEAREST_RADIUS_M / (111_320 * Math.cos((lat * Math.PI) / 180))
  const res = await client.getStopsInViewport({
    minLat: lat - dLat,
    minLng: lng - dLng,
    maxLat: lat + dLat,
    maxLng: lng + dLng,
  })
  let best: Stop | null = null
  let bestD = Infinity
  for (const s of res.stops) {
    const d = metresBetween(lat, lng, s.lat, s.lng)
    if (d < bestD) {
      bestD = d
      best = s
    }
  }
  if (!best) return null
  // Mirror StopSearch.pickStop: a stand indicator is appended to the name, and a rail station's CRS
  // becomes the short code and drives the live train board.
  const name = best.indicator ? `${best.name} (${best.indicator})` : best.name
  return { atcoCode: best.atcoCode, name, shortName: best.crs || undefined, stopType: best.stopType, crs: best.crs }
}
