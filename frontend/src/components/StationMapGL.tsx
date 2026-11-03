import { useEffect, useMemo, useRef, useState } from 'react'
import type maplibregl from 'maplibre-gl'
import Map, { NavigationControl, Source, Layer, Popup } from 'react-map-gl/maplibre'
import type { ViewStateChangeEvent, MapEvent, MapLayerMouseEvent, MapRef } from 'react-map-gl/maplibre'
import type { FeatureCollection } from 'geojson'
import 'maplibre-gl/dist/maplibre-gl.css'
import { BASEMAP_STYLE } from './mapgl/basemap'
import { MELTON_TOWN_CENTRE } from '../constants/stops'
import { RecenterControl, ShareViewControl } from './mapgl/MapControls'
import { BusRoutesGL, BUS_ROUTES_HIT_LAYER } from './mapgl/BusRoutesGL'
import { firstLabelLayerId } from './mapgl/layerOrder'
import { RouteOverlayGL, SERVICE_STOPS_LAYER } from './mapgl/RouteOverlayGL'
import type { RouteLegendItem } from '../lib/routeLegend'
import { StopsGL, STOPS_LAYER } from './mapgl/StopsGL'
import { BusMarkersGL } from './mapgl/BusMarkersGL'
import { StopSearch } from './StopSearch'
import type { BusVehicle } from '../proto/tfm/v1/buses_pb'
import type { ServiceStop } from '../proto/tfm/v1/routes_pb'
import type { LatLng } from '../proto/tfm/v1/common_pb'
import mapStyles from './StationMapGL.module.css'
import type { Bbox } from '../hooks/useBusVehicles'
import type { SelectedStop } from '../App'

/**
 * The app's main map — react-map-gl (MapLibre GL). The default since the Leaflet → MapLibre cutover
 * and is lazy-loaded by App so maplibre lands in its own chunk.
 *
 * Selection-controlled by App (boards/panel live there): takes onSelectStop/selectedAtcoCode, the bus
 * feed + onSelectVehicle, focus/follow nonces, the selected route, and the open service's route
 * (routeStops/routeShape → RouteOverlayGL). Basemap: hosted OpenFreeMap Liberty (mapgl/basemap.ts). Overlays are GL Source/Layer except live vehicles, which are imperative HTML
 * markers driven by the shared useVehicleAnimation. Layer stacking via mapgl/layerOrder.ts (beforeId).
 */

// Open centred on the town centre (Market Place), not the rail station.
const MELTON: [number, number] = [MELTON_TOWN_CENTRE.lng, MELTON_TOWN_CENTRE.lat]

/** Share-link deep view: ?lat&lng&zoom (ports StationMap's viewDeepLink). */
function readViewDeepLink(): { longitude: number; latitude: number; zoom: number } | null {
  const p = new URLSearchParams(window.location.search)
  const lat = Number(p.get('lat'))
  const lng = Number(p.get('lng'))
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || (lat === 0 && lng === 0)) return null
  const zoom = Number(p.get('zoom'))
  return { longitude: lng, latitude: lat, zoom: Number.isFinite(zoom) && zoom > 0 ? zoom : 13 }
}

/** Build a full App SelectedStop from a clicked stops-layer feature's properties. */
function stopFromFeature(p: Record<string, unknown>): SelectedStop {
  const id = String(p.id)
  const name = String(p.name ?? '')
  const stopType = p.stopType ? String(p.stopType) : undefined
  if (p.kind === 'rail') {
    const crs = String(p.crs ?? '')
    return { atcoCode: id, name, shortName: crs || undefined, crs: crs || undefined, stopType }
  }
  if (p.group) {
    const group = JSON.parse(String(p.group)) as { atcoCode: string; indicator: string }[]
    return { atcoCode: id, name, stopType, group }
  }
  const indicator = p.indicator ? String(p.indicator) : undefined
  return { atcoCode: id, name, shortName: indicator, stopType }
}

interface Props {
  /** App-driven stop/station selection — drives the board panel (boards live in App). */
  onSelectStop?: (stop: SelectedStop | null) => void
  selectedAtcoCode?: string | null
  /** Reports the viewport so App's bus feed (and the board's live features) stay scoped to it. */
  onBusViewport?: (b: Bbox | null) => void
  /** App's live buses (single shared feed) → rendered as markers; click selects the service. */
  vehicles?: BusVehicle[]
  onSelectVehicle?: (id: string) => void
  /** "Fly here" signal from the board (a clicked calling point); nonce re-fires repeats. */
  focusTarget?: { lat: number; lng: number; nonce: number } | null
  /** The vehicle to recentre on, and a nonce bumped by "Recentre on bus" / selecting a bus. */
  followVehicle?: { id: string; lat: number; lng: number } | null
  followNonce?: number
  /** Reports whether the camera is currently locked onto the bus (released on user pan), so the board's
   *  button can toggle between "Stop following" and "Recentre on bus". */
  onFollowChange?: (engaged: boolean) => void
  /** Bumped to disengage bus-follow from the board ("Stop following"). */
  cancelFollowNonce?: number
  /** Distinct routes in view → App's RouteLegend (the route-colour key). */
  onViewRoutesChange?: (items: RouteLegendItem[]) => void
  /** Selected route (from App) → highlights its path; click a single-route road to select. */
  selectedRouteKey?: string | null
  onSelectRoute?: (route: { shortName: string; longName: string; operator: string; color: string }) => void
  /** The open service's route, drawn on the map: line geometry + calling-point dots (RouteOverlayGL). */
  routeStops?: ServiceStop[]
  routeShape?: LatLng[]
  drawRouteLine?: boolean
  /** `${sequence}-${atcoCode}` of the calling point the board is focused on (enlarged dot). */
  highlightedStopKey?: string | null
  /** Frame the route on change — off while following a vehicle (App passes `selectedVehicleId === null`). */
  routeAutoFit?: boolean

}

export default function StationMapGL({
  onSelectStop,
  selectedAtcoCode = null,
  onBusViewport,
  vehicles,
  onSelectVehicle,
  focusTarget,
  followVehicle,
  followNonce,
  onFollowChange,
  cancelFollowNonce,
  onViewRoutesChange,
  selectedRouteKey = null,
  onSelectRoute,
  routeStops = [],
  routeShape = [],
  drawRouteLine = true,
  highlightedStopKey = null,
  routeAutoFit = true,
}: Props) {
  const mapRef = useRef<MapRef | null>(null)

  // Calling-point popup (clicking a route dot): the stop's name/locality/scheduled time on this service.
  const [routeStopPopup, setRouteStopPopup] = useState<
    { longitude: number; latitude: number; name: string; locality: string; scheduledTime: string } | null
  >(null)
  // Empty-space clicks clear the selection, but double-tap-to-zoom also emits click events on the map —
  // so a zoom gesture would wipe the route highlight. Defer the clear briefly and cancel it if a
  // double-tap (dblclick) lands inside the window.
  const clearTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const cancelPendingClear = () => {
    if (clearTimer.current) {
      clearTimeout(clearTimer.current)
      clearTimer.current = null
    }
  }

  // WebGL is required — surface a clear message rather than a blank canvas if it's unavailable.
  const webglOk = useMemo(() => {
    try {
      const c = document.createElement('canvas')
      return !!(c.getContext('webgl2') || c.getContext('webgl'))
    } catch {
      return false
    }
  }, [])


  const initialView = useMemo(
    () => readViewDeepLink() ?? { longitude: MELTON[0], latitude: MELTON[1], zoom: 15 },
    [],
  )

  // Viewport reporter: feed App's bus feed a viewport Bbox on move. Snapped
  // outward to ~0.01° so small pans don't churn subscriptions (the hooks key on numeric bounds).
  const onBusViewportRef = useRef(onBusViewport)
  onBusViewportRef.current = onBusViewport
  const syncViewport = (map: maplibregl.Map) => {
    const b = map.getBounds()
    const lo = (n: number) => Math.floor(n * 100) / 100
    const hi = (n: number) => Math.ceil(n * 100) / 100
    const box: Bbox = { minLat: lo(b.getSouth()), minLng: lo(b.getWest()), maxLat: hi(b.getNorth()), maxLng: hi(b.getEast()) }
    // Gate the bus feed by zoom (like Leaflet's BusViewportReporter) so zooming out doesn't pull the region.
    onBusViewportRef.current?.(map.getZoom() >= 12 ? box : null)
  }
  const onMoveEnd = (e: ViewStateChangeEvent) => syncViewport(e.target)

  // A pulse-less highlight ring marking the calling point the board focused (App's focusTarget).
  const focusData = useMemo<FeatureCollection>(
    () =>
      focusTarget
        ? { type: 'FeatureCollection', features: [{ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [focusTarget.lng, focusTarget.lat] } }] }
        : { type: 'FeatureCollection', features: [] },
    [focusTarget?.lat, focusTarget?.lng],
  )

  // The basemap's first text-label layer — overlays insert before it so they sit above all basemap
  // geometry (incl. bridges) but below labels. Must NOT be the first *symbol* layer (that's the
  // one-way arrows, which sit below the bridge layers, hiding routes at river/rail crossings).
  const [firstLabelId, setFirstLabelId] = useState<string | undefined>(undefined)
  const onLoad = (e: MapEvent) => {
    setFirstLabelId(firstLabelLayerId(e.target))
    syncViewport(e.target) // seed viewport so buses stream from first paint
  }

  // ── Selection drives App: stops → board panel; routes → route panel + highlight (both App state).
  const onMapClick = (e: MapLayerMouseEvent) => {
    cancelPendingClear() // any click cancels a deselect that an earlier empty-space tap scheduled
    // Calling-point dots sit above the stop symbols (as the Leaflet CircleMarkers did) — a dot click
    // shows its scheduled time on this service, and must not fall through to the empty-space deselect.
    const cp = e.features?.find((ft) => ft.layer.id === SERVICE_STOPS_LAYER)
    if (cp) {
      const p = cp.properties ?? {}
      const [lng, lat] = cp.geometry.type === 'Point' ? (cp.geometry.coordinates as [number, number]) : [e.lngLat.lng, e.lngLat.lat]
      setRouteStopPopup({
        longitude: lng,
        latitude: lat,
        name: String(p.name ?? ''),
        locality: String(p.locality ?? ''),
        scheduledTime: String(p.scheduledTime ?? ''),
      })
      return
    }
    // Stops sit on top — a stop click wins over a route under it. A stop selection opens its board.
    const stop = e.features?.find((ft) => ft.layer.id === STOPS_LAYER)
    if (stop) {
      onSelectStop?.(stopFromFeature(stop.properties ?? {})) // App clears any route selection too
      return
    }
    const route = e.features?.find((ft) => ft.layer.id === BUS_ROUTES_HIT_LAYER)
    if (route) {
      const p = route.properties ?? {}
      const sole = p.soleRoute as string
      // Single-route road → select it (opens App's route panel); shared roads use the legend chips.
      if (sole) {
        onSelectRoute?.({
          shortName: sole,
          longName: (p.soleLong as string) ?? '',
          operator: (p.soleOperator as string) ?? '',
          color: (p.soleColor as string) ?? '#1565c0',
        })
      }
      return
    }
    // Empty space → clear stop + route, but defer so a double-tap-to-zoom (which fires click events)
    // doesn't wipe the selection; onDblClick cancels this within the window.
    clearTimer.current = setTimeout(() => onSelectStop?.(null), 250)
  }
  const onMouseMove = (e: MapLayerMouseEvent) => {
    e.target.getCanvas().style.cursor = e.features?.length ? 'pointer' : ''
  }

  // Board → map camera. "Fly here" when a calling point is clicked; recentre on the bus when its
  // recentre button is pressed / it's selected. Keyed on the nonces so repeats re-fire; the floating
  // board sits top-right, so nudge the target left of centre to keep it clear.
  useEffect(() => {
    if (!focusTarget) return
    const m = mapRef.current
    if (m) m.easeTo({ center: [focusTarget.lng, focusTarget.lat], zoom: Math.max(m.getZoom(), 15), offset: [-120, 0], duration: 600 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusTarget?.nonce])
  // Recentre + ENGAGE continuous follow when the recentre button is pressed / a bus is selected.
  // followingRef is the synchronous source of truth for the per-fix recentre effects; followEngaged
  // mirrors it as state so the engaged/released status can be reported up (the board's toggle button).
  const followingRef = useRef(false)
  const [followEngaged, setFollowEngaged] = useState(false)
  const setFollowing = (v: boolean) => { followingRef.current = v; setFollowEngaged(v) }
  useEffect(() => { onFollowChange?.(followEngaged) }, [followEngaged, onFollowChange])
  // "Stop following" from the board → disengage (camera stays put; per-fix recentre stops).
  useEffect(() => {
    if (cancelFollowNonce) setFollowing(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cancelFollowNonce])
  useEffect(() => {
    if (!followNonce || !followVehicle) return
    setFollowing(true)
    const m = mapRef.current
    if (m) m.easeTo({ center: [followVehicle.lng, followVehicle.lat], zoom: Math.max(m.getZoom(), 15), offset: [-120, 0], duration: 600 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [followNonce])
  // Continuous follow: while engaged, recentre on each new bus fix (keeping the user's zoom). Released
  // when the user pans (onDragStart below); re-engaged by the recentre button.
  useEffect(() => {
    if (!followingRef.current || !followVehicle) return
    mapRef.current?.easeTo({ center: [followVehicle.lng, followVehicle.lat], offset: [-120, 0], duration: 900 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [followVehicle?.lat, followVehicle?.lng])

  if (!webglOk) {
    return (
      <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', font: '14px system-ui' }}>
        This map needs WebGL, which isn’t available in this browser.
      </div>
    )
  }

  return (
    <div className={mapStyles.root} style={{ position: 'absolute', inset: 0 }}>
      {/* Stop/place search — floats over the map (its own positioning), flies via MapLibre. */}
      <StopSearch
        onPick={(s) => onSelectStop?.(s)}
        onFlyTo={(lat, lng, zoom) => mapRef.current?.flyTo({ center: [lng, lat], zoom, duration: 600 })}
      />
      <Map
        ref={mapRef}
        initialViewState={initialView}
        mapStyle={BASEMAP_STYLE}
        attributionControl={{ compact: true }}
        onMoveEnd={onMoveEnd}
        onLoad={onLoad}
        onDragStart={() => setFollowing(false)} // user takes the camera → stop following
        interactiveLayerIds={[SERVICE_STOPS_LAYER, STOPS_LAYER, BUS_ROUTES_HIT_LAYER]}
        onClick={onMapClick}
        onDblClick={cancelPendingClear} // double-tap-to-zoom must not deselect the route/stop
        onMouseMove={onMouseMove}
        style={{ position: 'absolute', inset: 0 }}
      >
        {/* Bus routes — quiet network + highlight on select. */}
        <BusRoutesGL selectedRouteKey={selectedRouteKey} firstLabelId={firstLabelId} onLegend={onViewRoutesChange} />
        {/* Calling-point popup: the stop's name/locality + its scheduled time on the open service. */}
        {routeStops.length > 0 && routeStopPopup && (
          <Popup
            longitude={routeStopPopup.longitude}
            latitude={routeStopPopup.latitude}
            anchor="bottom"
            closeOnClick={false}
            onClose={() => setRouteStopPopup(null)}
          >
            <div style={{ font: '12px system-ui, sans-serif', lineHeight: 1.5 }}>
              <strong>{routeStopPopup.name}</strong>
              {routeStopPopup.locality && (
                <>
                  <br />
                  {routeStopPopup.locality}
                </>
              )}
              {routeStopPopup.scheduledTime && (
                <>
                  <br />
                  <span style={{ fontFamily: 'monospace' }}>{routeStopPopup.scheduledTime}</span>
                </>
              )}
            </div>
          </Popup>
        )}
        {/* Stops + stations — symbol layer on top; click opens its board (via App). */}
        <StopsGL selectedAtcoCode={selectedAtcoCode} />
        {/* Selected service's route — line + calling-point dots, drawn above the stops (as in Leaflet). */}
        {routeStops.length > 0 && (
          <RouteOverlayGL
            stops={routeStops}
            shape={routeShape}
            drawLine={drawRouteLine}
            anchorAtco={selectedAtcoCode}
            highlightedKey={highlightedStopKey}
            autoFit={routeAutoFit}
          />
        )}
        {/* Live buses — HTML markers via the shared animator (App owns the feed); click → service board. */}
        <BusMarkersGL vehicles={vehicles ?? []} onSelectVehicle={onSelectVehicle} />
        {/* Highlight ring for the focused calling point (clicked in the board). */}
        <Source id="focus-dot" type="geojson" data={focusData}>
          <Layer
            id="focus-dot"
            type="circle"
            paint={{
              'circle-radius': 9,
              'circle-color': '#d2691e',
              'circle-opacity': 0.2,
              'circle-stroke-color': '#d2691e',
              'circle-stroke-width': 2.5,
            }}
          />
        </Source>
        <NavigationControl position="bottom-left" />
        <RecenterControl center={MELTON} zoom={15} position="bottom-left" />
        <ShareViewControl position="bottom-left" />
      </Map>

    </div>
  )
}
