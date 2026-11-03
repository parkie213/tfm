import { useEffect, useRef, useState } from 'react'
import type { BusVehicle, GetBusVehiclesResponse } from '../proto/tfm/v1/buses_pb'
import { client } from '../transport'
import type { AnimatableVehicle } from './useVehicleAnimation'
import { useServerStream } from './useServerStream'

/**
 * Maps live buses to animator inputs, preferring the server's road-snapped position over the raw GPS
 * fix whenever it's present (`snapped`), so every bus sits on the carriageway — not just the followed
 * one. Falls back to the raw fix when the bus isn't matched to a trip / has no shape yet / is too far
 * off route to snap confidently. The followed bus's animator additionally refines this via its route
 * geometry, but starting from the snapped point keeps it reliably in route mode.
 */
export function busesToAnimatable(vehicles: BusVehicle[]): AnimatableVehicle[] {
  return vehicles.map((v) => ({
    id: v.id,
    lat: v.snapped ? v.snappedLat : v.lat,
    lng: v.snapped ? v.snappedLng : v.lng,
    bearing: v.snapped && v.snappedBearing >= 0 ? v.snappedBearing : v.bearing,
    // Drives the prediction speed model: a real m/s from the recorded_at delta between fixes.
    recordedAt: v.recordedAt,
    // The server's previous fix — seeds prediction so a bus moves on the first frame after load.
    // 0/"" when only one fix is buffered → no seed (the bus starts moving on its next fix instead).
    prevLat: v.prevRecordedAt ? v.prevLat : undefined,
    prevLng: v.prevRecordedAt ? v.prevLng : undefined,
    prevRecordedAt: v.prevRecordedAt || undefined,
  }))
}

/** Viewport bounding box (already snapped/padded by the caller); null = zoomed out → no buses. */
export interface Bbox {
  minLat: number
  minLng: number
  maxLat: number
  maxLng: number
}

export interface UseBusVehiclesResult {
  vehicles: BusVehicle[]
  generatedAt: string | null
  snapshotAgeSeconds: number
  stale: boolean
  /** true until the first snapshot of the current subscription arrives (drives "Locating bus…"). */
  loading: boolean
  /** true when the server has not configured a BODS key (gRPC UNIMPLEMENTED) */
  unimplemented: boolean
}

/**
 * Subscribes to the server-streamed live bus positions for the current map viewport. The server pushes
 * the current snapshot on subscribe and after every poll; we replace state per message. `trackedTripId`
 * keeps that service's vehicle in the stream even when it's outside the viewport (so a board-picked
 * service stays locatable/followable while its bus is off-screen) — this folds in what used to be a
 * second whole-feed poll (`useTrackedVehicle`). `bounds === null` (zoomed out) shows no buses.
 */
export function useBusVehicles(bounds: Bbox | null, trackedTripId: string | null = null): UseBusVehiclesResult {
  const [vehicles, setVehicles] = useState<BusVehicle[]>([])
  const [generatedAt, setGeneratedAt] = useState<string | null>(null)
  const [snapshotAgeSeconds, setSnapshotAgeSeconds] = useState(0)
  const [stale, setStale] = useState(false)
  const [loading, setLoading] = useState(true)

  const minLat = bounds?.minLat ?? 0
  const minLng = bounds?.minLng ?? 0
  const maxLat = bounds?.maxLat ?? 0
  const maxLng = bounds?.maxLng ?? 0
  const hasBounds = bounds !== null
  const tracked = trackedTripId ?? ''

  // Skip re-applying an identical snapshot (same generatedAt) — within one subscription the content
  // is fixed per poll, so this drops the occasional duplicate (initial push racing a broadcast).
  const lastGeneratedAt = useRef<string | null>(null)

  // Clear when the viewport goes away (zoomed out below the min zoom).
  useEffect(() => {
    if (!hasBounds) {
      setVehicles([])
      setLoading(false)
    }
  }, [hasBounds])

  const { unavailable, error } = useServerStream<GetBusVehiclesResponse>({
    enabled: hasBounds,
    deps: [minLat, minLng, maxLat, maxLng, tracked],
    makeStream: (signal) =>
      client.streamBusVehicles(
        { minLat, minLng, maxLat, maxLng, trackedTripId: tracked },
        { signal },
      ),
    onReset: () => {
      lastGeneratedAt.current = null
      setLoading(true)
    },
    onMessage: (resp) => {
      setLoading(false)
      if (resp.generatedAt && resp.generatedAt === lastGeneratedAt.current) return
      lastGeneratedAt.current = resp.generatedAt
      setVehicles(resp.vehicles)
      setGeneratedAt(resp.generatedAt || null)
      setSnapshotAgeSeconds(resp.snapshotAgeSeconds)
      setStale(resp.stale)
    },
  })

  // Resolve "loading" once the feed is off (UNIMPLEMENTED) or sustained-failing — otherwise the
  // "Locating bus…" spinner that reads `buses.loading` spins forever. Callers then fall through to the
  // "live position unavailable" message instead of an eternal spinner.
  return {
    vehicles,
    generatedAt,
    snapshotAgeSeconds,
    stale,
    loading: loading && !unavailable && !error,
    unimplemented: unavailable,
  }
}
