import { useState, useEffect, useRef, useMemo, lazy, Suspense } from 'react'
import { RouteLegend } from './components/RouteLegend'
import type { RouteLegendItem } from './lib/routeLegend'
import { BoardPanel } from './components/BoardPanel'
import { BottomSheet } from './components/BottomSheet'
import type { SheetSnap } from './components/BottomSheet'
import { TfmLogo } from './components/TfmLogo'
import { NavMenu } from './components/NavMenu'
import { useServiceStops } from './hooks/useDepartures'
import { useBusVehicles, type Bbox } from './hooks/useBusVehicles'
import { useLiveTime } from './hooks/useLiveTime'
import { useTrainService } from './hooks/useTrainService'
import type { SelectedTrainService } from './hooks/useTrainService'
import { NearMeButton } from './components/NearMeButton'
import { findNearbyStops } from './lib/nearestStop'
import type { ServiceStop } from './proto/tfm/v1/routes_pb'
import type { TrainCallingPoint } from './proto/tfm/v1/trains_pb'
import styles from './App.module.css'

// Lazy-loaded so maplibre-gl lands in its own chunk (shared with the lazy TVView) rather than bloating
// the main bundle — the app shell (header, search, boards) paints while the map streams in.
const StationMapGL = lazy(() => import('./components/StationMapGL'))

/** Map a TrainCallingPoint to the ServiceStop shape that RouteOverlayGL expects. */
function callingPointToServiceStop(cp: TrainCallingPoint): ServiceStop {
  return {
    $typeName: 'tfm.v1.ServiceStop',
    sequence: cp.sequence,
    atcoCode: cp.tpl,
    name: cp.name,
    locality: '',
    lat: cp.lat,
    lng: cp.lng,
    scheduledTime: cp.departure || cp.arrival,
  }
}

export interface SelectedStop {
  atcoCode: string
  name: string
  // Short code/indicator shown after the name as "Name | CODE" (e.g. a stop stand, or a CRS).
  shortName?: string
  // NaPTAN stop type (BCT/BCS/BCQ = bus, RLY/RSE = rail, MET = metro). Selects bus vs train board.
  stopType?: string
  // National Rail CRS for rail stations; empty/absent → no live train board (bus board / no-board message).
  crs?: string
  // When set, this is a GROUP of co-located stands (e.g. a bus station's S1–S4): the panel shows one
  // combined board merging all of them, each row tagged with the stand it leaves from.
  group?: { atcoCode: string; indicator: string }[]
}

export interface SelectedService {
  tripId: string
  line: string
  destination: string
  // From the board row: the service has a live vehicle right now (is_live), or is running but
  // unlocatable (no_gps). Drives the "Locate bus on map" / "Live position unavailable" affordances.
  // Absent when the service was opened by clicking the bus itself (already tracking).
  isLive?: boolean
  noGps?: boolean
}

export interface SelectedRoute {
  shortName: string
  longName: string
  operator: string
  color: string
}

export default function App() {
  const [boardCollapsed, setBoardCollapsed] = useState(false)
  const [selectedStop, setSelectedStop] = useState<SelectedStop | null>(null)
  const [selectedService, setSelectedService] = useState<SelectedService | null>(null)
  // A bus route picked by clicking its line on the map (shows a route panel; highlights the route).
  const [selectedRoute, setSelectedRoute] = useState<SelectedRoute | null>(null)
  // Bumped to (re)engage camera-follow on the tracked bus. Follow is otherwise released the moment the
  // user pans or focuses a stop — never forced back — so this is the explicit "snap back to the bus".
  const [followNonce, setFollowNonce] = useState(0)
  // Whether the GL camera is currently locked onto the bus (reported by the map; released on user pan),
  // and a nonce to disengage it — so the board's button toggles "Stop following" ⇄ "Recentre on bus".
  const [vehicleFollowEngaged, setVehicleFollowEngaged] = useState(false)
  const [cancelFollowNonce, setCancelFollowNonce] = useState(0)
  // Distinct routes currently drawn by the "all routes" overlay, for the colour legend.
  const [viewRoutes, setViewRoutes] = useState<RouteLegendItem[]>([])
  // Id of the live bus being tracked (drives map-follow + derived punctuality); null = not tracking.
  const [selectedVehicleId, setSelectedVehicleId] = useState<string | null>(null)
  // Current padded map viewport — the bus feed is scoped to this (null = zoomed out → no buses).
  const [busBounds, setBusBounds] = useState<Bbox | null>(null)
  // One-way "fly the map here" signal (panel → map); nonce makes repeat clicks re-fire.
  const [focusTarget, setFocusTarget] = useState<{
    lat: number
    lng: number
    nonce: number
    bottomObscuredPx: number
  } | null>(null)
  const focusNonce = useRef(0)
  // The calling point a user clicked in the open service's stop list — highlighted on the map's
  // route overlay. Keyed "sequence-atcoCode" to match RouteOverlayGL's dots. Null = none.
  const [highlightedStopKey, setHighlightedStopKey] = useState<string | null>(null)
  // Mobile bottom-sheet snap state — used to offset map flyTo targets above the sheet.
  const sheetSnap = useRef<SheetSnap>('half')
  const [sheetSnapRequest, setSheetSnapRequest] = useState<{ snap: SheetSnap; nonce: number } | null>(null)

  // Train service selected from the station board (mutually exclusive with bus selectedService).
  const [selectedTrainService, setSelectedTrainService] = useState<SelectedTrainService | null>(null)
  const serviceStops = useServiceStops(selectedService?.tripId ?? null)
  const trainServiceResult = useTrainService(selectedTrainService)
  const liveTime = useLiveTime()
  const [nearMeLoading, setNearMeLoading] = useState(false)

  // Calling points → RouteOverlayGL-compatible stops + shape (filtered to valid coords).
  const trainRouteStops = useMemo<ServiceStop[]>(() => {
    const stops = trainServiceResult.data?.stops
    if (!stops || stops.length === 0) return []
    return stops.map(callingPointToServiceStop)
  }, [trainServiceResult.data])

  // The bus for a service picked from the board is usually off-screen (it serves this stop later, so
  // it's elsewhere right now). Ask the stream to keep the selected trip's vehicle in the feed
  // even outside the viewport box, so the map can render/follow it. Null = viewport buses only.
  const serviceTripToTrack =
    selectedService
      ? selectedService.tripId || null
      : null

  // Live buses are owned here so the tracked vehicle, route, and follow target share one stream.
  // The tracked trip is
  // folded in server-side, so `buses.vehicles` already carries the off-screen bus — no client merge.
  const buses = useBusVehicles(busBounds, serviceTripToTrack)
  const vehicles = buses.vehicles

  const selectedVehicle = useMemo(
    () => (selectedVehicleId ? vehicles.find((v) => v.id === selectedVehicleId) ?? null : null),
    [vehicles, selectedVehicleId],
  )
  // The live vehicle currently operating the selected (board-picked) service, if any — lets the user
  // jump to "where is this service now". Resolved from the merged feed, so it finds the bus even when
  // it's off-screen. Null once a bus is already being tracked (then it IS that bus).
  const liveVehicleForService = useMemo(() => {
    if (!selectedService || selectedVehicleId) return null
    const tripId = selectedService.tripId
    if (!tripId) return null
    return vehicles.find((v) => v.tripId === tripId) ?? null
  }, [selectedService, selectedVehicleId, vehicles])

  // "Where is it now" sub-states for a scheduled (untracked) service: still looking up the all-buses
  // feed, vs. looked and found no live position. The latter is told
  // to the user rather than left as a silently-missing button.
  const liveLocating = !!serviceTripToTrack && !selectedVehicleId && !liveVehicleForService && buses.loading
  const liveUnavailable =
    !!selectedService &&
    !selectedVehicleId &&
    !liveVehicleForService &&
    // Only after we've actually looked: when zoomed out (busBounds null) the bus stream is disabled, so
    // "no live position" would be a false claim — we simply never queried for it.
    busBounds !== null &&
    !buses.loading &&
    !!serviceTripToTrack

  // Selecting a live bus opens its service detail (reusing the calling-points view) and tracks it.
  function handleSelectVehicle(id: string) {
    const v = buses.vehicles.find((veh) => veh.id === id)
    if (!v) return
    setSelectedVehicleId(id)
    setSelectedService({ tripId: v.tripId, line: v.line, destination: v.destination })
    setSelectedStop(null)
    setSelectedRoute(null)
    setFollowNonce((n) => n + 1) // fly to it on selection
  }

  // "Locate bus on map" from a scheduled service that has a matching live vehicle → start tracking it.
  function handleLocateVehicle(id: string) {
    setSelectedVehicleId(id)
    setFollowNonce((n) => n + 1)
  }

  // A board row is a scheduled service, not a live vehicle — stop tracking when one is picked.
  function handleSelectService(svc: SelectedService) {
    setSelectedVehicleId(null)
    setSelectedService(svc)
    setSelectedRoute(null)
    setSelectedTrainService(null)
  }

  // A station board train row was clicked.
  function handleSelectTrainService(svc: SelectedTrainService) {
    setSelectedTrainService(svc)
    setSelectedService(null)
    setSelectedVehicleId(null)
    setSelectedRoute(null)
  }

  function handleClearTrainService() {
    setSelectedTrainService(null)
  }

  // Clicking a route line on the map (or its legend entry) shows the route panel and highlights it.
  // Clicking the already-selected route toggles it off. Mutually exclusive with the other panels.
  function handleSelectRoute(route: SelectedRoute) {
    if (selectedRoute?.shortName === route.shortName) {
      setSelectedRoute(null)
      return
    }
    setSelectedRoute(route)
    setSelectedService(null)
    setSelectedVehicleId(null)
    setSelectedStop(null)
  }

  function focusStop(lat: number, lng: number, stopKey?: string) {
    setHighlightedStopKey(stopKey ?? null)
    focusNonce.current += 1
    let bottomObscuredPx = 0
    if (window.matchMedia('(max-width: 700px)').matches) {
      // A fully-open sheet hides the map — drop it to half so the stop is visible.
      if (sheetSnap.current === 'full') {
        setSheetSnapRequest({ snap: 'half', nonce: focusNonce.current })
      }
      const snapAfter = sheetSnap.current === 'peek' ? 'peek' : 'half'
      bottomObscuredPx = snapAfter === 'half' ? window.innerHeight * 0.5 : 76
    }
    setFocusTarget({ lat, lng, nonce: focusNonce.current, bottomObscuredPx })
  }

  // A stop highlight belongs to one open service — drop it when the service (or train service)
  // changes or closes, so a stale dot doesn't linger on the next route.
  useEffect(() => {
    setHighlightedStopKey(null)
  }, [selectedService?.tripId, selectedTrainService])

  // Single-page map app — strip any stale routing hash (e.g. the old "#/trains").
  useEffect(() => {
    if (window.location.hash) {
      window.history.replaceState(null, '', window.location.pathname + window.location.search)
    }
  }, [])

  function handleSelectStop(stop: SelectedStop | null) {
    setSelectedStop(stop)
    // Selecting a different stop (or clearing) dismisses any active service detail / tracked bus / route.
    setSelectedService(null)
    setSelectedVehicleId(null)
    setSelectedRoute(null)
    setSelectedTrainService(null)
  }

  function handleClearService() {
    setSelectedService(null)
    setSelectedVehicleId(null)
    setSelectedTrainService(null)
  }

  // "What can I catch near me?" — locate the device, then open a merged board of the nearest bus stops
  // (the existing combined-board flow, via a synthetic grouped stop). Falls back to the single nearest
  // stop (e.g. a rail station) if no bus stops are close. Tapping a departure opens its service details.
  function handleNearMe() {
    if (!navigator.geolocation || nearMeLoading) return
    setNearMeLoading(true)
    navigator.geolocation.getCurrentPosition(
      async (geo) => {
        try {
          const stops = await findNearbyStops(geo.coords.latitude, geo.coords.longitude, { limit: 10 })
          const bus = stops.filter((s) => s.stop.stopType.startsWith('B'))
          if (bus.length > 0) {
            handleSelectStop({
              atcoCode: bus[0].stop.atcoCode,
              name: 'Near you',
              group: bus.map((s) => ({ atcoCode: s.stop.atcoCode, indicator: s.stop.name })),
            })
          } else if (stops.length > 0) {
            const s = stops[0].stop
            handleSelectStop({
              atcoCode: s.atcoCode,
              name: s.indicator ? `${s.name} (${s.indicator})` : s.name,
              shortName: s.crs || undefined,
              stopType: s.stopType,
              crs: s.crs || undefined,
            })
          }
        } finally {
          setNearMeLoading(false)
        }
      },
      () => setNearMeLoading(false),
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 60_000 },
    )
  }

  // Map-first shell: the board only exists while something is selected. "Close" (the panel's clear
  // buttons) drops the selection → bare full-screen map; "collapse" minimises it to a chip.
  const showBoard = !!(selectedStop || selectedService || selectedRoute || selectedTrainService)
  // A fresh selection always opens expanded (don't reappear as a chip from a previous collapse).
  useEffect(() => {
    if (showBoard) setBoardCollapsed(false)
  }, [selectedStop, selectedService, selectedRoute, selectedTrainService, showBoard])

  return (
    <div className={styles.app}>
      <main className={styles.main}>
        <section className={styles.mapSection}>
          {/* Brand pill (with the nav burger inline, so it clears the map's zoom controls) */}
          <div className={`${styles.brandPill} glass-panel`}>
            <TfmLogo size={36} color="var(--brand)" amplitude={0.14} />
            <div className={styles.brandText}>
              <div className={styles.brandName}>Transport for Melton</div>
              <div className={styles.brandTagline}>powered by pies &#x1F967;</div>
            </div>
            <NavMenu placement="inline" />
          </div>

          {/* Legend + live status (bottom-left status bar) */}
          <div className={`${styles.legend} glass-panel`}>
            <span className={styles.legendItem}>
              <span className={styles.legendDot} style={{ background: 'var(--brand)' }} />
              Bus
            </span>
            <span className={styles.legendSep} />
            <span className={styles.legendItem}>
              <span className={styles.legendDot} style={{ background: 'var(--rail)' }} />
              Rail
            </span>
            <span className={styles.legendSep} />
            <span className={styles.legendItem}>
              <span className={styles.legendDot} style={{ background: 'var(--pr)' }} />
              Park &amp; Ride
            </span>
            <span className={styles.legendSep} />
            <span className={styles.legendLive}>
              <span className={styles.liveDot} aria-hidden="true">
                <span className={styles.liveDotPing} />
                <span className={styles.liveDotCore} />
              </span>
              Live &middot; {liveTime}
            </span>
          </div>

          {/* MapLibre GL map in the full App shell — boards/panel come from App's BoardPanel below. */}
          <Suspense fallback={null}>
          <StationMapGL
            onSelectStop={handleSelectStop}
            selectedAtcoCode={selectedStop?.atcoCode ?? null}
            onBusViewport={setBusBounds}
            vehicles={vehicles}
            onSelectVehicle={handleSelectVehicle}
            focusTarget={focusTarget}
            followVehicle={
              selectedVehicle ? { id: selectedVehicle.id, lat: selectedVehicle.lat, lng: selectedVehicle.lng } : null
            }
            followNonce={followNonce}
            onFollowChange={setVehicleFollowEngaged}
            cancelFollowNonce={cancelFollowNonce}
            onViewRoutesChange={setViewRoutes}
            selectedRouteKey={selectedRoute?.shortName ?? null}
            onSelectRoute={handleSelectRoute}
            routeStops={selectedTrainService ? trainRouteStops : (serviceStops.data?.stops ?? [])}
            routeShape={selectedTrainService ? [] : (serviceStops.data?.shape ?? [])}
            drawRouteLine={!selectedTrainService}
            highlightedStopKey={highlightedStopKey}
            routeAutoFit={selectedVehicleId === null}
          />
          </Suspense>

          <div className={styles.mapActions}>
            {!showBoard && <NearMeButton loading={nearMeLoading} onClick={handleNearMe} />}
            <RouteLegend
              routes={viewRoutes}
              selectedRouteKey={selectedRoute?.shortName ?? null}
              onSelect={handleSelectRoute}
            />
          </div>
        </section>

        {showBoard && (
        <BottomSheet
          className={`${styles.sidebar} ${boardCollapsed ? styles.sidebarCollapsed : ''}`}
          onSnapChange={(snap) => { sheetSnap.current = snap }}
          snapRequest={sheetSnapRequest}
        >
          <BoardPanel
            collapsed={boardCollapsed}
            onToggleCollapse={() => setBoardCollapsed((v) => !v)}
            selectedStop={selectedStop}
            onClearStop={() => handleSelectStop(null)}
            selectedService={selectedService}
            onSelectService={handleSelectService}
            onClearService={handleClearService}
            selectedRoute={selectedRoute}
            onClearRoute={() => setSelectedRoute(null)}
            serviceStops={serviceStops}
            onFocusStop={focusStop}
            isLiveVehicle={selectedVehicleId !== null}
            liveVehicleId={liveVehicleForService?.id ?? null}
            liveLocating={liveLocating}
            liveUnavailable={liveUnavailable}
            onLocateVehicle={handleLocateVehicle}
            onRecenterVehicle={() => setFollowNonce((n) => n + 1)}
            isFollowingVehicle={vehicleFollowEngaged && selectedVehicleId !== null}
            onCancelFollowVehicle={() => setCancelFollowNonce((n) => n + 1)}
            selectedTrainService={selectedTrainService}
            onSelectTrainService={handleSelectTrainService}
            onClearTrainService={handleClearTrainService}
            trainServiceResult={trainServiceResult}
          />
        </BottomSheet>
        )}
      </main>
    </div>
  )
}
