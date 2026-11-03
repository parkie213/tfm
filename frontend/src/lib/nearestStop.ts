import { client } from '../transport'
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
  const res = await client.getNearbyStops({ lat, lng, radiusM, limit })
  return res.stops.flatMap(s => s.stop ? [{ stop: s.stop, distanceM: s.distanceM }] : [])
}

/** Search the area around a point for the closest stop or station, mapped to a SelectedStop. */
export async function findNearestStop(lat: number, lng: number): Promise<SelectedStop | null> {
  const best = (await findNearbyStops(lat, lng, { limit: 1 }))[0]?.stop
  if (!best) return null
  // Mirror StopSearch.pickStop: a stand indicator is appended to the name, and a rail station's CRS
  // becomes the short code and drives the live train board.
  const name = best.indicator ? `${best.name} (${best.indicator})` : best.name
  return { atcoCode: best.atcoCode, name, shortName: best.crs || undefined, stopType: best.stopType, crs: best.crs }
}
