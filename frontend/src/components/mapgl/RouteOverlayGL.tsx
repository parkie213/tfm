import { useEffect, useMemo, useRef } from 'react'
import { Source, Layer, useMap } from 'react-map-gl/maplibre'
import type { FeatureCollection, Feature, Point, LineString } from 'geojson'
import type { ExpressionSpecification } from 'maplibre-gl'
import type { ServiceStop } from '../../proto/tfm/v1/routes_pb'
import type { LatLng } from '../../proto/tfm/v1/common_pb'

/**
 * The selected service's route drawn on the GL map — a coloured line (the operator's real geometry when
 * present, else straight lines through the calling points) plus a dot at every calling point, with the
 * board's focused stop enlarged/recoloured. A faithful port of the Leaflet `RouteOverlay` in
 * StationMap.tsx: same colours, the same "anchor on the selected stop, otherwise fit the whole route"
 * camera behaviour, and the same route-key refit trigger. Calling-point popups are handled by
 * StationMapGL's central onMapClick (the dot layer is in interactiveLayerIds) — the name/locality/time
 * ride on each point's feature properties.
 */

const ROUTE_COLOR = '#d6006e'
const HIGHLIGHT_COLOR = '#f97316'
export const SERVICE_STOPS_LAYER = 'service-route-stops'

// Data-driven dot styling keyed on the `hot` property (the board's highlighted calling point).
const HOT: ExpressionSpecification = ['get', 'hot']
const DOT_RADIUS: ExpressionSpecification = ['case', HOT, 9, 5]
const DOT_COLOR: ExpressionSpecification = ['case', HOT, HIGHLIGHT_COLOR, ROUTE_COLOR]
const DOT_STROKE: ExpressionSpecification = ['case', HOT, 3, 2]
const DOT_SORT: ExpressionSpecification = ['case', HOT, 1, 0] // highlighted dot drawn on top

interface Props {
  stops: ServiceStop[]
  shape: LatLng[]
  /** Disable the route line while retaining calling-point markers. */
  drawLine?: boolean
  /** The selected stop's atco — anchor the camera here (stay put) rather than fitting the whole route. */
  anchorAtco?: string | null
  /** `${sequence}-${atcoCode}` of the calling point the board is focused on. */
  highlightedKey?: string | null
  /** Frame the route on change. Off while following a vehicle (the camera tracks the vehicle instead). */
  autoFit?: boolean
}

export function RouteOverlayGL({ stops, shape, drawLine = true, anchorAtco = null, highlightedKey = null, autoFit = true }: Props) {
  const map = useMap().current

  // Stops with valid coordinates (exclude NaPTAN misses where lat/lng are 0/0).
  const coordStops = useMemo(() => stops.filter((s) => s.lat !== 0 || s.lng !== 0), [stops])

  // Prefer the operator's real road geometry (GTFS shape); fall back to straight lines through stops.
  const lineCoords = useMemo<[number, number][]>(
    () => (shape.length >= 2 ? shape.map((p) => [p.lng, p.lat]) : coordStops.map((s) => [s.lng, s.lat])),
    [shape, coordStops],
  )

  const lineData = useMemo<FeatureCollection<LineString>>(
    () => ({
      type: 'FeatureCollection',
      features:
        drawLine && lineCoords.length >= 2
          ? [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: lineCoords } }]
          : [],
    }),
    [lineCoords, drawLine],
  )

  const pointData = useMemo<FeatureCollection<Point>>(
    () => ({
      type: 'FeatureCollection',
      features: coordStops.map(
        (s): Feature<Point> => ({
          type: 'Feature',
          geometry: { type: 'Point', coordinates: [s.lng, s.lat] },
          properties: {
            // sequence + atco: a looping route revisits a stop, so atco alone isn't a unique key.
            hot: `${s.sequence}-${s.atcoCode}` === highlightedKey,
            name: s.name,
            locality: s.locality ?? '',
            scheduledTime: s.scheduledTime ?? '',
          },
        }),
      ),
    }),
    [coordStops, highlightedKey],
  )

  // Frame the route whenever the service changes. Key on the RAW first stop — its atcoCode is present even
  // when its coordinate is a 0/0 NaPTAN miss; stop count disambiguates routes sharing a first stop. (Same
  // strategy as the Leaflet RouteOverlay; refit only when the route itself changes, not when autoFit flips.)
  const routeKey = `${stops.length}:${stops[0]?.atcoCode ?? ''}`
  const lastKey = useRef<string | null>(null)
  useEffect(() => {
    const m = map?.getMap()
    if (!m || !autoFit || lineCoords.length < 1) {
      lastKey.current = routeKey
      return
    }
    if (lastKey.current === routeKey) return
    lastKey.current = routeKey
    // Opened from a stop board: stay anchored on the selected stop (and never zoom out) rather than
    // fitting the whole route, which swallows the map for a regional service. The board docks to the
    // right, so nudge the target left (offset) to keep it clear — matching StationMapGL's other moves.
    const anchor = anchorAtco ? coordStops.find((s) => s.atcoCode === anchorAtco) : null
    if (anchor) {
      m.easeTo({ center: [anchor.lng, anchor.lat], zoom: Math.max(m.getZoom(), 15), offset: [-120, 0], duration: 500 })
      return
    }
    // No anchor (e.g. a deep link): fit the whole route, with extra right padding for the board.
    let minLng = Infinity
    let minLat = Infinity
    let maxLng = -Infinity
    let maxLat = -Infinity
    for (const [lng, lat] of lineCoords) {
      if (lng < minLng) minLng = lng
      if (lat < minLat) minLat = lat
      if (lng > maxLng) maxLng = lng
      if (lat > maxLat) maxLat = lat
    }
    m.fitBounds(
      [
        [minLng, minLat],
        [maxLng, maxLat],
      ],
      { padding: { top: 40, bottom: 40, left: 40, right: 140 }, maxZoom: 15, duration: 500 },
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeKey, autoFit])

  if (lineCoords.length < 2 && coordStops.length === 0) return null

  return (
    <>
      {/* White casing under a vivid line for contrast against any basemap. */}
      <Source id="service-route-line" type="geojson" data={lineData}>
        <Layer
          id="service-route-casing"
          type="line"
          layout={{ 'line-join': 'round', 'line-cap': 'round' }}
          paint={{ 'line-color': '#ffffff', 'line-width': 8, 'line-opacity': 0.9 }}
        />
        <Layer
          id="service-route"
          type="line"
          layout={{ 'line-join': 'round', 'line-cap': 'round' }}
          paint={{ 'line-color': ROUTE_COLOR, 'line-width': 4 }}
        />
      </Source>
      {/* Calling-point dots; the highlighted one is enlarged/recoloured and sorted on top. Clicks → popup
          via StationMapGL.onMapClick (SERVICE_STOPS_LAYER is registered interactive there). */}
      <Source id="service-route-stops" type="geojson" data={pointData}>
        <Layer
          id={SERVICE_STOPS_LAYER}
          type="circle"
          layout={{ 'circle-sort-key': DOT_SORT }}
          paint={{
            'circle-radius': DOT_RADIUS,
            'circle-color': DOT_COLOR,
            'circle-stroke-color': '#ffffff',
            'circle-stroke-width': DOT_STROKE,
          }}
        />
      </Source>
    </>
  )
}
