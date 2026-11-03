import type { Map as MlMap } from 'maplibre-gl'

/**
 * The fixed stacking order for our overlay layers — MapLibre's replacement for Leaflet's z-indexed
 * panes (liveTrains/liveBuses/userLocation/routeNetwork/railNetwork). MapLibre draws layers in array
 * order, so instead of z-index we insert each overlay *before* the next one already on the map.
 *
 * Bottom → top. User location sits above everything (was pane z680); rail network is the quiet
 * underlay (was z240). Live vehicles are HTML markers (DOM, always above the canvas) — listed here
 * only so anything added relative to them resolves correctly.
 */
export const OVERLAY_ORDER = [
  'rail-network', // quiet national-rail underlay
  'bus-routes', // viewport bus route lines
  'route-highlight', // selected route emphasis
  'stops', // stop/station symbols
  'vehicles', // live buses/trains (HTML markers in practice)
  'user-location', // "you are here" + you-vs-estimate leash/circle
] as const

export type OverlayId = (typeof OVERLAY_ORDER)[number]

/**
 * The layer id to insert `id` *before* so the OVERLAY_ORDER is preserved regardless of the order
 * overlays are actually added — returns the first higher overlay already on the map, or undefined
 * (add on top) if none is. Pass the result as the `beforeId` arg to map.addLayer / react-map-gl's
 * Layer `beforeId`.
 */
export function beforeId(map: MlMap, id: OverlayId): string | undefined {
  const idx = OVERLAY_ORDER.indexOf(id)
  for (let i = idx + 1; i < OVERLAY_ORDER.length; i++) {
    if (map.getLayer(OVERLAY_ORDER[i])) return OVERLAY_ORDER[i]
  }
  return undefined
}

/**
 * The basemap layer that our line overlays (bus routes, rail network) should be inserted *before*,
 * so they sit above ALL basemap geometry — roads, bridges, rail, boundaries, buildings — yet below
 * the text labels.
 *
 * NOT simply "the first symbol layer": in the OpenMapTiles/Liberty style the first symbol layer is
 * `road_one_way_arrow`, which sits *among* the road/bridge line layers (below every `bridge_*`
 * layer). Inserting a route there draws it underneath the bridge layers, so it vanishes wherever it
 * crosses a bridge — i.e. at every river crossing and grade-separated railway crossing. Instead we
 * take the first symbol layer that comes *after* the last geometry (line/fill/etc.) layer: that's
 * the first real text label, which is guaranteed to be above the bridges. Returns undefined (add on
 * top) if the style has no labels.
 */
export function firstLabelLayerId(map: MlMap): string | undefined {
  const layers = map.getStyle().layers ?? []
  const occludes = (t: string) => t !== 'symbol' && t !== 'background' && t !== 'raster'
  let lastGeomIdx = -1
  for (let i = 0; i < layers.length; i++) {
    if (occludes(layers[i].type)) lastGeomIdx = i
  }
  for (let i = lastGeomIdx + 1; i < layers.length; i++) {
    if (layers[i].type === 'symbol') return layers[i].id
  }
  return undefined
}
