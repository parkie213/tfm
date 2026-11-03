import { useEffect, useRef, type MutableRefObject } from 'react'
import {
  bearingBetween,
  clamp,
  cumulativeDistances,
  destPoint,
  metresBetween,
  pointAtDistance,
  snapToPolyline,
  type Pt,
} from '../lib/route'

/** Minimal shape the animator needs; used by live bus markers. */
export interface AnimatableVehicle {
  id: string
  lat: number
  lng: number
  bearing: number // 0-359, or -1 if unknown
  // Optional, used only by forward-prediction (buses) — the speed model derives a real m/s from the
  // recorded_at delta between fixes, and seeds motion at load from the previous fix the server ships.
  recordedAt?: string // RFC3339 time of this fix
  prevLat?: number // previous fix (server-supplied) — seed velocity so the marker moves on the first frame
  prevLng?: number
  prevRecordedAt?: string
}

/** The route to snap the matched vehicle onto (the followed bus's timetabled road + stops). */
export interface SnapRoute {
  vehicleId: string
  shape: Pt[]
  stops: Pt[] // calling points — used as the extrapolation stop-clamp
}

/** Per-vehicle animation state, held in a ref (never React state — must not trigger re-renders). */
export interface VehicleAnim {
  mode: 'line' | 'route'
  // line mode — straight-line between the last two fixes:
  fromLat: number
  fromLng: number
  toLat: number
  toLng: number
  startMs: number
  durationMs: number
  // route mode — extrapolate chainage along the snapped route:
  fixS: number // last REAL snapped chainage (never a predicted value)
  vEma: number // smoothed speed (m/s) — route chainage, or straight-line for line-prediction
  eAtFix: number // reconcile error (rendered − fix) captured when the last fix arrived
  // line-prediction mode (buses) — dead-reckon forward from the last fix along a movement bearing:
  fixLat: number // anchor: last REAL fix position
  fixLng: number
  course: number // movement bearing (deg) the marker is predicted along
  eLat: number // 2D reconcile error (rendered − fix), decayed over RECONCILE_MS so corrections ease in
  eLng: number
  recordedAtMs: number // recorded_at of the current anchor (epoch ms); 0 if unknown → fall back to wall-clock
  // common:
  s: number // current rendered chainage (route mode)
  lat: number
  lng: number
  bearing: number
  appliedBearing: number // last bearing written to the arrow element (avoids redundant DOM writes)
  appliedOpacity: number // last opacity written to the marker element
  lastFixMs: number
  seenMove: boolean // false until the first real movement is processed (first move = teleport, not glide)
}

export type VehicleAnimRef = MutableRefObject<Map<string, VehicleAnim>>
/**
 * The minimal marker surface the animator drives — `setLatLng([lat,lng])` to move it and `getElement()`
 * for the arrow/opacity writes. Leaflet's `L.Marker` satisfies this structurally; a MapLibre marker
 * wrapper (setLngLat + getElement) satisfies it too, so the animator is map-library-agnostic.
 */
export interface AnimMarker {
  setLatLng(latlng: [number, number]): unknown
  getElement(): HTMLElement | undefined
}

export type MarkerRef = MutableRefObject<Map<string, AnimMarker>>

interface PreparedRoute {
  vehicleId: string
  shape: Pt[]
  cum: number[]
  routeLen: number
  stopChainages: number[] // sorted ascending
}

const FRAME_MS = 1000 / 15 // 15fps — indistinguishable from 60 for a slow-moving bus, far cheaper
const MIN_GLIDE_MS = 4_000
const MAX_GLIDE_MS = 45_000
const MOVE_EPSILON_M = 2 // below this a "new" fix is GPS jitter, not movement
const MAX_SNAP_M = 50 // a fix further than this from the route isn't on it → ENTER route mode at/below this
const ROUTE_LEAVE_M = 120 // hysteresis: once snapped, stay in route mode until a fix is THIS far off
const MAX_GLIDE_JUMP_M = 2_000 // beyond this a line-mode fix is implausible → teleport, don't sail across the map
const MAX_GLIDE_SPEED_MS = 28 // ~100 km/h — a faster implied glide is a stale-fix catch-up → teleport

// ---- Extrapolation (matched route bus only) ----
const EMA_ALPHA = 0.35 // speed smoothing; ~2-3 sample memory at 30s fixes
const EXTRAP_BIAS = 0.7 // conservative under-prediction — corrections become gentle forward catch-ups
const EXTRAP_CAP_MS = 30_000 // cap forward prediction at ~1 fix interval, then hold
const MAX_SPEED_MS = 22 // ~50mph hard cap on the speed estimate (guards against bad-snap spikes)
const MAX_FWD_M = 150 // never predict more than this far past the last fix (bounds any rubber-band)
const RECONCILE_MS = 4_000 // error-decay timescale (projective velocity blending)
const HARD_SNAP_M = 50 // backward correction beyond this → snap, never reverse the bus on screen
const STOPPED_M = 45 // displacement below this (~3σ urban GPS) → treat as stopped, v=0
const NEXT_STOP_EPS_M = 5 // ignore stops at/just behind the current fix when finding the next one
const CONF_FADE_MS = 45_000 // icon opacity fades to MIN_OPACITY over this fix age (visible uncertainty)
const MIN_OPACITY = 0.4
const ARROW_DEG_THRESH = 3 // only rewrite the arrow rotation when it changes meaningfully

/**
 * Drives smooth, continuously-moving map markers from sparse position fixes, imperatively (outside
 * React's render path). React owns the *set* of markers; this hook owns each marker's per-frame
 * position via `setLatLng`.
 *
 * Motion model (expert-validated):
 *  - The matched bus (`route.vehicleId`) is snapped onto its route polyline and **extrapolated** along
 *    the road for freshness: EMA speed projected forward, biased gently low, clamped at the next stop,
 *    capped at ~one fix interval, reconciled smoothly on each fix (with a hard-snap on big backward
 *    errors so the bus never reverses). Stationary buses are detected and held. Icon opacity decays
 *    with fix age to make prediction uncertainty visible. The arrow points along the road (tangent).
 *  - With `opts.extrapolate` (buses), every other bus ALSO dead-reckons forward — a straight-line port
 *    of the route-mode model: EMA speed (from the real recorded_at delta, so a stale seed can't rocket
 *    the marker), projected along the last movement bearing, capped, reconciled on each fix.
 *  - Without it (trains, already server-interpolated between timing points) markers straight-line glide
 *    between the last two fixes; an implausibly large jump teleports rather than sailing across the map.
 */
export function useVehicleAnimation<T extends AnimatableVehicle>(
  vehicles: T[],
  pollIntervalMs: number,
  route: SnapRoute | null = null,
  opts: { extrapolate?: boolean } = {},
): { markerRefs: MarkerRef; animState: VehicleAnimRef } {
  const markerRefs = useRef<Map<string, AnimMarker>>(new Map())
  const animState = useRef<Map<string, VehicleAnim>>(new Map())
  const prevById = useRef<Map<string, T>>(new Map())
  const routeRef = useRef<PreparedRoute | null>(null)
  // Forward-prediction is opt-in per hook instance (buses on, trains off). Held in a ref so the
  // empty-deps rAF loop reads the current value without re-subscribing.
  const extrapolateRef = useRef(opts.extrapolate ?? false)
  extrapolateRef.current = opts.extrapolate ?? false

  // Precompute route distances + stop chainages when the matched route changes.
  const routeKey = route ? `${route.vehicleId}:${route.shape.length}:${route.stops.length}` : null
  useEffect(() => {
    if (!route || route.shape.length < 2) {
      routeRef.current = null
      return
    }
    const cum = cumulativeDistances(route.shape)
    const routeLen = cum[cum.length - 1]
    const stopChainages = route.stops
      .filter((p) => p.lat !== 0 || p.lng !== 0)
      .map((p) => snapToPolyline(p, route.shape, cum)?.s ?? -1)
      .filter((s) => s >= 0)
      .sort((a, b) => a - b)
    routeRef.current = { vehicleId: route.vehicleId, shape: route.shape, cum, routeLen, stopChainages }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeKey])

  // Reconcile animation state when a new snapshot arrives. Runs at poll cadence, not per frame.
  useEffect(() => {
    const now = performance.now()
    const next = new Map<string, T>()
    for (const v of vehicles) next.set(v.id, v)

    for (const id of [...animState.current.keys()]) {
      if (!next.has(id)) animState.current.delete(id)
    }

    const prepared = routeRef.current

    for (const v of vehicles) {
      const st = animState.current.get(v.id)
      const prev = prevById.current.get(v.id)
      const onRoute = prepared != null && v.id === prepared.vehicleId

      if (!st) {
        animState.current.set(v.id, freshState(v, now, onRoute ? prepared : null, extrapolateRef.current))
        continue
      }

      const moved = !prev || v.lat !== prev.lat || v.lng !== prev.lng
      if (!moved) {
        // Same position. For a prediction bus, a NEWER upstream fix at the same spot means it's
        // parked-but-live: refresh the fix time so its confidence opacity doesn't fade to "stale", and
        // zero the speed so we don't dead-reckon it forward off the stop. (A truly stale feed — same
        // recorded_at — is left to fade honestly.) Trains don't fade, so this is a no-op for them.
        if (extrapolateRef.current && st && prev && v.recordedAt && v.recordedAt !== prev.recordedAt) {
          st.lastFixMs = now
          st.vEma = 0
        }
        continue
      }

      // The first movement after a bus appears (e.g. just after page load) is a correction from its
      // initial — possibly stale — fix to a fresh one, not real travel. Snap to it rather than sailing
      // the marker across (and through buildings) to catch up. Real inter-fix motion glides thereafter.
      const firstMove = !st.seenMove
      st.seenMove = true

      const from = { lat: st.lat, lng: st.lng }
      const to = { lat: v.lat, lng: v.lng }
      const measured = st.lastFixMs ? now - st.lastFixMs : pollIntervalMs

      // Route mode for the matched bus — snap + reconcile + update the speed model.
      if (onRoute && prepared) {
        const wasRoute = st.mode === 'route'
        // Hysteresis: easy to ENTER route mode (≤MAX_SNAP_M), sticky to LEAVE it (>ROUTE_LEAVE_M), so a
        // single noisy fix can't knock a bus off its route line and straight back on.
        const threshold = wasRoute ? ROUTE_LEAVE_M : MAX_SNAP_M
        const prior = wasRoute ? st.s : (snapToPolyline(from, prepared.shape, prepared.cum)?.s ?? null)
        const snapped = snapToPolyline(to, prepared.shape, prepared.cum, prior)
        if (import.meta.env.DEV) {
          const d = snapped ? `${snapped.dist.toFixed(0)}m` : '∞'
          const m = snapped && snapped.dist <= threshold ? 'route' : 'LINE (off-route)'
          // eslint-disable-next-line no-console
          console.debug(`[bus ${v.id}] snapDist=${d} (≤${threshold}) → ${m}  s=${snapped?.s.toFixed(0)} routeLen=${prepared.routeLen.toFixed(0)}`)
        }
        if (snapped && snapped.dist <= threshold) {
          const targetS = snapped.s
          if (firstMove || !wasRoute) {
            // Entering route mode (just selected) or the bus's first move after appearing: snap onto
            // the road and DON'T seed the speed model from a stale initial fix — that's what rocketed
            // the marker forward along the route to "catch up". Real speed builds from the next fixes.
            st.mode = 'route'
            st.s = targetS
            st.fixS = targetS
            st.vEma = 0
            st.eAtFix = 0
            st.lastFixMs = now
            continue
          }
          const disp = targetS - st.fixS // real chainage moved since the previous real fix
          const dt = measured / 1000
          if (Math.abs(disp) < STOPPED_M) {
            st.vEma = 0 // stopped — do not extrapolate (prevents drift off a stop)
          } else if (dt > 0) {
            const vInst = Math.max(0, disp / dt) // forward speed from REAL fixes only
            st.vEma = Math.min(EMA_ALPHA * vInst + (1 - EMA_ALPHA) * st.vEma, MAX_SPEED_MS)
          }
          let e = wasRoute ? st.s - targetS : 0
          if (e > HARD_SNAP_M) {
            e = 0 // would reverse the bus too far — snap to the fix instead
            st.s = targetS
          }
          st.mode = 'route'
          st.fixS = targetS
          st.eAtFix = e
          st.lastFixMs = now
          continue
        }
        // else: off-route fix → fall through to line mode
      }

      // Line mode. `jump` (rendered→fix) gates jitter/teleport; `from` is the rendered position.
      const jump = metresBetween(from, to)
      const impliedSpeed = jump / Math.max(measured / 1000, 1)
      const garbage = jump > MAX_GLIDE_JUMP_M || impliedSpeed > MAX_GLIDE_SPEED_MS
      st.mode = 'line'

      if (extrapolateRef.current) {
        // ── Forward prediction (buses) — straight-line port of route mode ───────────────────────
        const anchor = { lat: st.fixLat, lng: st.fixLng } // previous REAL fix
        const disp = metresBetween(anchor, to) // real inter-fix travel
        const recNow = recordedAtMs(v)
        // Prefer the real recorded_at delta over wall-clock: a stale seed then yields a sane speed
        // instead of compressing a long gap into one poll and rocketing the marker forward.
        const dt = st.recordedAtMs > 0 && recNow > 0 ? (recNow - st.recordedAtMs) / 1000 : measured / 1000

        // 1) Speed + course, from REAL fixes only.
        if (jump < MOVE_EPSILON_M) {
          // jitter — keep predicting with the current speed/course
        } else if (garbage) {
          st.vEma = 0 // implausible fix — stop predicting until clean fixes resume
        } else if (disp < STOPPED_M) {
          st.vEma = 0 // dwelling at a stop — hold position
        } else if (dt > 0) {
          const vInst = Math.min(disp / dt, MAX_SPEED_MS)
          st.vEma = Math.min(EMA_ALPHA * vInst + (1 - EMA_ALPHA) * st.vEma, MAX_SPEED_MS)
          st.course = v.bearing >= 0 ? v.bearing : bearingBetween(anchor, to)
        }

        // 2) Position: snap on first/implausible move (don't sail across), else reconcile by decaying
        // the rendered→fix offset to zero so the marker eases onto the fix rather than jumping.
        if (firstMove || garbage) {
          st.lat = to.lat; st.lng = to.lng
          st.eLat = 0; st.eLng = 0
        } else {
          let eLat = st.lat - to.lat
          let eLng = st.lng - to.lng
          if (metresBetween({ lat: st.lat, lng: st.lng }, to) > HARD_SNAP_M) {
            eLat = 0; eLng = 0; st.lat = to.lat; st.lng = to.lng // never reverse far — snap instead
          }
          st.eLat = eLat; st.eLng = eLng
        }
        st.fixLat = to.lat; st.fixLng = to.lng
        st.recordedAtMs = recNow
        st.lastFixMs = now
        continue
      }

      // ── Straight-line interpolation (trains) — teleport on jitter / implausible jump / first move ──
      const durationMs = clamp(measured, MIN_GLIDE_MS, MAX_GLIDE_MS)
      st.lastFixMs = now
      if (firstMove || jump < MOVE_EPSILON_M || garbage) {
        st.fromLat = to.lat; st.fromLng = to.lng
        st.toLat = to.lat; st.toLng = to.lng
        st.lat = to.lat; st.lng = to.lng
        continue
      }
      st.fromLat = from.lat; st.fromLng = from.lng
      st.toLat = to.lat; st.toLng = to.lng
      st.startMs = now
      st.durationMs = durationMs
      st.bearing = v.bearing >= 0 ? v.bearing : bearingBetween(from, to)
    }

    prevById.current = next
  }, [vehicles, pollIntervalMs])

  // One global rAF loop, throttled. Reads refs only, so empty deps are correct (no stale closures).
  useEffect(() => {
    let raf = 0
    let lastFrame = 0
    const tick = (t: number) => {
      raf = requestAnimationFrame(tick)
      if (t - lastFrame < FRAME_MS) return
      lastFrame = t
      const now = performance.now()
      const prepared = routeRef.current
      for (const [id, st] of animState.current) {
        let opacity = 1
        if (st.mode === 'route' && prepared && prepared.vehicleId === id) {
          const age = now - st.lastFixMs
          const decay = Math.max(0, 1 - age / RECONCILE_MS)
          const fwd = Math.min((st.vEma * EXTRAP_BIAS * Math.min(age, EXTRAP_CAP_MS)) / 1000, MAX_FWD_M)
          let s = st.fixS + fwd + st.eAtFix * decay
          const nextStop = nextStopChainage(prepared.stopChainages, st.fixS, prepared.routeLen)
          if (s > nextStop) s = nextStop // clamp: never predict past the next stop
          st.s = clamp(s, 0, prepared.routeLen)
          const at = pointAtDistance(prepared.shape, prepared.cum, st.s)
          st.lat = at.lat; st.lng = at.lng; st.bearing = at.bearing
          opacity = clamp(1 - age / CONF_FADE_MS, MIN_OPACITY, 1) // fade with fix age = visible confidence
        } else if (extrapolateRef.current) {
          // Line-prediction (buses): dead-reckon forward along the movement bearing from the last fix,
          // plus the decaying reconcile offset so a new fix eases in rather than snaps.
          const age = now - st.lastFixMs
          const decay = Math.max(0, 1 - age / RECONCILE_MS)
          const fwd = Math.min((st.vEma * EXTRAP_BIAS * Math.min(age, EXTRAP_CAP_MS)) / 1000, MAX_FWD_M)
          const p = destPoint({ lat: st.fixLat, lng: st.fixLng }, st.course, fwd)
          st.lat = p.lat + st.eLat * decay
          st.lng = p.lng + st.eLng * decay
          st.bearing = st.course
          opacity = clamp(1 - age / CONF_FADE_MS, MIN_OPACITY, 1)
        } else {
          const k = st.durationMs <= 0 ? 1 : Math.min((now - st.startMs) / st.durationMs, 1)
          st.lat = st.fromLat + (st.toLat - st.fromLat) * k
          st.lng = st.fromLng + (st.toLng - st.fromLng) * k
        }

        const marker = markerRefs.current.get(id)
        if (!marker) continue
        marker.setLatLng([st.lat, st.lng])
        applyArrowAndOpacity(marker, st, opacity)
      }
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [])

  return { markerRefs, animState }
}

/** The chainage of the first stop downstream of `fromS`, or the route end if none remain. */
function nextStopChainage(sorted: number[], fromS: number, routeLen: number): number {
  for (const s of sorted) {
    if (s > fromS + NEXT_STOP_EPS_M) return s
  }
  return routeLen
}

/** Rotate the divIcon arrow to the road tangent and apply confidence opacity — compositor-only writes. */
function applyArrowAndOpacity(marker: AnimMarker, st: VehicleAnim, opacity: number): void {
  const el = marker.getElement()
  if (!el) return
  if (Math.abs(st.bearing - st.appliedBearing) > ARROW_DEG_THRESH) {
    // Bus markers carry `.bus-badge__arrow`, train markers `.train-badge__arrow`; match either so the
    // shared animator rotates both (was bus-only, so train arrows never turned).
    const arrow = el.querySelector('.bus-badge__arrow, .train-badge__arrow') as HTMLElement | null
    if (arrow) {
      arrow.style.transform = `rotate(${Math.round(st.bearing)}deg)`
      st.appliedBearing = st.bearing
    }
  }
  if (Math.abs(opacity - st.appliedOpacity) > 0.02) {
    el.style.opacity = opacity === 1 ? '' : String(opacity)
    st.appliedOpacity = opacity
  }
}

/** recorded_at as epoch ms, or 0 if absent/unparseable. */
function recordedAtMs(v: AnimatableVehicle): number {
  if (!v.recordedAt) return 0
  const ms = Date.parse(v.recordedAt)
  return Number.isNaN(ms) ? 0 : ms
}

/**
 * Initial state for a newly-seen vehicle — placed exactly. Snapped if it's the matched bus. For
 * forward-prediction buses, if the server shipped the previous fix we seed the speed model from the
 * two real fixes (using their recorded_at delta) so the marker moves on the FIRST frame rather than
 * sitting still until a second fix lands.
 */
function freshState(
  v: AnimatableVehicle,
  now: number,
  prepared: PreparedRoute | null,
  extrapolate: boolean,
): VehicleAnim {
  let mode: 'line' | 'route' = 'line'
  let lat = v.lat
  let lng = v.lng
  let s = 0
  if (prepared) {
    const snap = snapToPolyline(v, prepared.shape, prepared.cum)
    if (snap && snap.dist <= MAX_SNAP_M) {
      mode = 'route'
      lat = snap.lat; lng = snap.lng; s = snap.s
    }
  }

  // Seed straight-line prediction from the server's previous fix (line-mode buses only).
  let vEma = 0
  let course = v.bearing >= 0 ? v.bearing : 0
  let seenMove = false
  if (extrapolate && mode === 'line' && v.prevLat !== undefined && v.prevLng !== undefined) {
    const prev = { lat: v.prevLat, lng: v.prevLng }
    const curr = { lat, lng }
    const prevMs = v.prevRecordedAt ? Date.parse(v.prevRecordedAt) : NaN
    const dt = !Number.isNaN(prevMs) && recordedAtMs(v) > 0 ? (recordedAtMs(v) - prevMs) / 1000 : 0
    const disp = metresBetween(prev, curr)
    if (dt > 0 && disp >= STOPPED_M) {
      vEma = Math.min(disp / dt, MAX_SPEED_MS)
      course = v.bearing >= 0 ? v.bearing : bearingBetween(prev, curr)
      seenMove = true // we already have real motion — the next fix reconciles, it isn't a "first move"
    }
  }

  return {
    mode,
    fromLat: lat, fromLng: lng, toLat: lat, toLng: lng,
    startMs: now, durationMs: 1,
    fixS: s, vEma, eAtFix: 0,
    fixLat: lat, fixLng: lng, course, eLat: 0, eLng: 0, recordedAtMs: recordedAtMs(v),
    s, lat, lng, bearing: v.bearing,
    appliedBearing: -999, appliedOpacity: 1,
    lastFixMs: now,
    seenMove,
  }
}
