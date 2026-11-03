import { useEffect, useMemo, useRef, useState } from 'react'
import { Source, Layer, useMap } from 'react-map-gl/maplibre'
import type { FeatureCollection, Feature, LineString } from 'geojson'
import type { RouteTrunk } from '../../proto/tfm/v1/routes_pb'
import { client } from '../../transport'
import { smoothPath } from '../../lib/smoothPath'
import type { RouteLegendItem } from '../../lib/routeLegend'

/**
 * Bus routes as a QUIET NETWORK + highlight-on-select (no side-by-side lanes). Every road is drawn once
 * as a thin neutral line (the trunk centreline, smoothed); selecting a route lights its full path in its
 * colour on top. Because there's no perpendicular offset, centrelines meet exactly at junctions — no fan,
 * no gaps, no junction ramping/stitching needed (so no buildLines, no react-leaflet, no per-zoom
 * recompute). Geometry is plain lat/lng, built once per fetch.
 *
 * Selection: legend chips (primary, rendered by StationMapGL) + clicking a road that carries a single
 * route. A fat transparent hit layer makes roads easy to click.
 */

const MIN_ZOOM = 14
const DEBOUNCE_MS = 300
const SMOOTH_STEPS = 6
const NETWORK_COLOR = '#334155' // confident dark slate — pops on the light basemap, stays neutral

function numericPrefix(name: string): number {
  const m = /^\D*(\d+)/.exec(name)
  return m ? Number(m[1]) : Number.MAX_SAFE_INTEGER
}

/** Smoothed trunk centreline as GeoJSON coords ([lng,lat]). */
function centreline(trunk: RouteTrunk): [number, number][] {
  return smoothPath(
    trunk.shape.map((p) => [p.lat, p.lng] as [number, number]),
    SMOOTH_STEPS,
  ).map(([lat, lng]) => [lng, lat] as [number, number])
}

/** One neutral line per road; on single-route roads, carry the route's full info for click-to-select. */
function networkData(trunks: RouteTrunk[]): FeatureCollection {
  const features: Feature<LineString>[] = trunks
    .filter((t) => t.shape.length >= 2)
    .map((t) => {
      const single = t.routeShortName.length === 1
      return {
        type: 'Feature',
        geometry: { type: 'LineString', coordinates: centreline(t) },
        properties: single
          ? {
              soleRoute: t.routeShortName[0],
              soleLong: t.routeLongName[0] ?? '',
              soleOperator: t.operator[0] ?? '',
              soleColor: t.routeColor[0] ?? '#1565c0',
            }
          : { soleRoute: '' },
      }
    })
  return { type: 'FeatureCollection', features }
}

/** Every road carrying the selected route — drawn in its colour on top of the grey network. */
function selectedData(trunks: RouteTrunk[], routeKey: string): FeatureCollection {
  const features: Feature<LineString>[] = trunks
    .filter((t) => t.shape.length >= 2 && t.routeShortName.includes(routeKey))
    .map((t) => ({ type: 'Feature', geometry: { type: 'LineString', coordinates: centreline(t) }, properties: {} }))
  return { type: 'FeatureCollection', features }
}

interface Props {
  selectedRouteKey: string | null
  firstLabelId?: string
  onLegend?: (items: RouteLegendItem[]) => void
}

/** The hit layer id StationMapGL wires to interactiveLayerIds + onClick. */
export const BUS_ROUTES_HIT_LAYER = 'bus-routes-hit'

export function BusRoutesGL({ selectedRouteKey, firstLabelId, onLegend }: Props) {
  const mapRef = useMap().current
  const [trunks, setTrunks] = useState<RouteTrunk[]>([])
  const genRef = useRef(0)
  const abortRef = useRef<AbortController | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Viewport-scoped fetch (debounced, zoom-gated, latest-wins) — mirrors ViewportRoutes.doFetch.
  useEffect(() => {
    const map = mapRef?.getMap()
    if (!map) return
    const doFetch = () => {
      if (map.getZoom() < MIN_ZOOM) {
        abortRef.current?.abort()
        genRef.current++
        setTrunks([])
        return
      }
      const gen = ++genRef.current
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller
      const b = map.getBounds()
      const dLat = (b.getNorth() - b.getSouth()) * 0.25
      const dLng = (b.getEast() - b.getWest()) * 0.25
      client
        .getRouteShapesInViewport(
          {
            minLat: b.getSouth() - dLat,
            minLng: b.getWest() - dLng,
            maxLat: b.getNorth() + dLat,
            maxLng: b.getEast() + dLng,
          },
          { signal: controller.signal },
        )
        .then((resp) => {
          if (gen === genRef.current) setTrunks(resp.trunks)
        })
        .catch((err: unknown) => {
          if (err instanceof Error && (err.name === 'AbortError' || err.message.includes('abort'))) return
          if (gen === genRef.current) setTrunks([])
        })
    }
    const schedule = () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
      debounceRef.current = setTimeout(doFetch, DEBOUNCE_MS)
    }
    doFetch()
    map.on('moveend', schedule)
    return () => {
      map.off('moveend', schedule)
      abortRef.current?.abort()
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [mapRef])

  const network = useMemo(() => networkData(trunks), [trunks])
  const colorByRoute = useMemo(() => {
    const m = new Map<string, string>()
    for (const t of trunks) for (let i = 0; i < t.routeShortName.length; i++) {
      const sn = t.routeShortName[i]
      if (sn && !m.has(sn)) m.set(sn, t.routeColor[i] ?? '#1565c0')
    }
    return m
  }, [trunks])
  const selected = useMemo(
    () => (selectedRouteKey ? selectedData(trunks, selectedRouteKey) : null),
    [trunks, selectedRouteKey],
  )
  const selectedColor = selectedRouteKey ? colorByRoute.get(selectedRouteKey) ?? '#1565c0' : null

  // Legend: distinct routes in view (sorted by number).
  const onLegendRef = useRef(onLegend)
  onLegendRef.current = onLegend
  useEffect(() => {
    const seen = new Map<string, RouteLegendItem>()
    for (const t of trunks) for (let i = 0; i < t.routeShortName.length; i++) {
      const sn = t.routeShortName[i]
      if (sn && !seen.has(sn)) {
        seen.set(sn, { shortName: sn, longName: t.routeLongName[i] ?? '', color: t.routeColor[i] ?? '#1565c0', operator: t.operator[i] ?? '' })
      }
    }
    onLegendRef.current?.(
      [...seen.values()].sort((a, b) => numericPrefix(a.shortName) - numericPrefix(b.shortName) || a.shortName.localeCompare(b.shortName)),
    )
  }, [trunks])

  return (
    <>
      {/* Quiet network: white casing + grey line, drawn once per road. */}
      <Source id="bus-routes" type="geojson" data={network} tolerance={0}>
        <Layer
          id="bus-routes-casing"
          type="line"
          beforeId={firstLabelId}
          layout={{ 'line-cap': 'round', 'line-join': 'round' }}
          paint={{ 'line-color': '#ffffff', 'line-opacity': 0.95, 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 3, 16, 7] }}
        />
        <Layer
          id="bus-routes-network"
          type="line"
          beforeId={firstLabelId}
          layout={{ 'line-cap': 'round', 'line-join': 'round' }}
          paint={{ 'line-color': NETWORK_COLOR, 'line-opacity': 1, 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 1.8, 16, 4] }}
        />
        <Layer
          id={BUS_ROUTES_HIT_LAYER}
          type="line"
          beforeId={firstLabelId}
          layout={{ 'line-cap': 'round', 'line-join': 'round' }}
          paint={{ 'line-color': '#000000', 'line-opacity': 0, 'line-width': 14 }}
        />
      </Source>

      {/* Selected route: its colour, bold, on top of the network. */}
      {selected && selectedColor && (
        <Source id="bus-route-selected" type="geojson" data={selected} tolerance={0}>
          {/* Soft coloured glow under the line so the selected route really pops off the network. */}
          <Layer
            id="bus-route-selected-glow"
            type="line"
            beforeId={firstLabelId}
            layout={{ 'line-cap': 'round', 'line-join': 'round' }}
            paint={{
              'line-color': selectedColor,
              'line-opacity': 0.35,
              'line-blur': 3,
              'line-width': ['interpolate', ['linear'], ['zoom'], 12, 10, 16, 18],
            }}
          />
          <Layer
            id="bus-route-selected-casing"
            type="line"
            beforeId={firstLabelId}
            layout={{ 'line-cap': 'round', 'line-join': 'round' }}
            paint={{ 'line-color': '#ffffff', 'line-opacity': 0.95, 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 6, 16, 9.5] }}
          />
          <Layer
            id="bus-route-selected"
            type="line"
            beforeId={firstLabelId}
            layout={{ 'line-cap': 'round', 'line-join': 'round' }}
            paint={{ 'line-color': selectedColor, 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 4, 16, 7] }}
          />
        </Source>
      )}
    </>
  )
}
