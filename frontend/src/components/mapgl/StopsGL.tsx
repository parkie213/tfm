import { useEffect, useMemo, useRef, useState } from 'react'
import { Source, Layer, useMap } from 'react-map-gl/maplibre'
import type { FeatureCollection, Feature, Point } from 'geojson'
import type { ExpressionSpecification } from 'maplibre-gl'
import type { Stop } from '../../proto/tfm/v1/stops_pb'
import { client } from '../../transport'
import { groupBusStops } from '../../lib/stopGrouping'

/**
 * Stops + stations as a GL symbol layer (Phase 2). Bus/station glyphs are rendered to images via
 * map.addImage (the divIcon equivalent), so the layer scales to the viewport's hundreds of stops on the
 * GPU. Bus stands collapse via the existing groupBusStops; rail stations (RLY/RSE with a CRS) get the
 * train glyph. Viewport-fetched/debounced/zoom-gated like ViewportStops. Click selects (StationMapGL
 * wires the layer into interactiveLayerIds); selection grows the icon via an expression on `id`.
 *
 * Known spike gaps vs Leaflet: no group-count badge or hover name (need a glyph font / Popup — Phase 5).
 */

const MIN_ZOOM = 14
const DEBOUNCE_MS = 300
export const STOPS_LAYER = 'stops'

// Icon images (44px, drawn @2x): coloured rounded square + white glyph, reusing StationMap's paths.
const TRAIN_PATH =
  'M12 2c-4 0-8 .5-8 4v9.5A3.5 3.5 0 0 0 7.5 19L6 20.5v.5h12v-.5L16.5 19a3.5 3.5 0 0 0 3.5-3.5V6c0-3.5-4-4-8-4z' +
  'M7.5 17A1.5 1.5 0 1 1 9 15.5 1.5 1.5 0 0 1 7.5 17zM11 10H6V6h5v4zm2 0V6h5v4h-5zm3.5 7a1.5 1.5 0 1 1 1.5-1.5 1.5 1.5 0 0 1-1.5 1.5z'
const BUS_PATH =
  'M4 16c0 .88.39 1.67 1 2.22V20a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-1h8v1a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4S4 2.5 4 6v10z' +
  'M7.5 17A1.5 1.5 0 1 1 9 15.5 1.5 1.5 0 0 1 7.5 17zm9 0a1.5 1.5 0 1 1 1.5-1.5 1.5 1.5 0 0 1-1.5 1.5zM18 11H6V6h12v5z'
const icon = (bg: string, path: string) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="44" height="44" viewBox="0 0 44 44">` +
  `<rect x="3" y="3" width="38" height="38" rx="10" fill="${bg}" stroke="#fff" stroke-width="2.5"/>` +
  `<g transform="translate(10,10)" fill="#fff">${path}</g></svg>`

function loadIcon(svg: string): Promise<HTMLImageElement> {
  return new Promise((res, rej) => {
    const img = new Image(44, 44)
    img.onload = () => res(img)
    img.onerror = rej
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
  })
}

function buildData(stops: Stop[]): FeatureCollection {
  const features: Feature<Point>[] = []
  // Rail stations: RLY/RSE with a CRS (live board), one per CRS, RLY node preferred. Unlike the Leaflet
  // map (which drew a dedicated home-station marker), we keep Melton here so it's clickable like any other.
  const rail = stops
    .filter((s) => (s.stopType === 'RLY' || s.stopType === 'RSE') && s.crs)
    .sort((a, b) => (a.stopType === 'RLY' ? 0 : 1) - (b.stopType === 'RLY' ? 0 : 1))
  const seenCrs = new Set<string>()
  for (const s of rail) {
    if (seenCrs.has(s.crs)) continue
    seenCrs.add(s.crs)
    // id is the atcoCode (matches App's selectedAtcoCode); crs carried for the live train board.
    features.push({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [s.lng, s.lat] },
      properties: { id: s.atcoCode, kind: 'rail', name: s.name, crs: s.crs, stopType: s.stopType, count: '' },
    })
  }
  // Bus stops: collapse same-place stands into one point (groupBusStops), like ViewportStops.
  const { groups, singles } = groupBusStops(stops.filter((s) => s.stopType.startsWith('B')))
  for (const s of singles) {
    features.push({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [s.lng, s.lat] },
      properties: { id: s.atcoCode, kind: 'bus', name: s.name, indicator: s.indicator ?? '', stopType: s.stopType, count: '' },
    })
  }
  for (const g of groups) {
    features.push({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [g.lng, g.lat] },
      // count drives the stand-count badge; group (JSON) lets the combined board merge all stands.
      properties: {
        id: g.members[0].atcoCode,
        kind: 'bus',
        name: g.name,
        stopType: g.members[0].stopType,
        count: String(g.members.length),
        group: JSON.stringify(g.members.map((m) => ({ atcoCode: m.atcoCode, indicator: m.indicator }))),
      },
    })
  }
  return { type: 'FeatureCollection', features }
}

interface Props {
  selectedAtcoCode: string | null
}

export function StopsGL({ selectedAtcoCode }: Props) {
  const mapRef = useMap().current
  const [stops, setStops] = useState<Stop[]>([])
  const [iconsReady, setIconsReady] = useState(false)
  const genRef = useRef(0)
  const abortRef = useRef<AbortController | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Register the glyph images once (idempotent across HMR / style reloads).
  useEffect(() => {
    const map = mapRef?.getMap()
    if (!map) return
    let cancelled = false
    Promise.all([loadIcon(icon('#0891b2', BUS_PATH)), loadIcon(icon('#cc2233', TRAIN_PATH))])
      .then(([bus, rail]) => {
        if (cancelled) return
        if (!map.hasImage('bus-stop')) map.addImage('bus-stop', bus, { pixelRatio: 2 })
        if (!map.hasImage('rail-stop')) map.addImage('rail-stop', rail, { pixelRatio: 2 })
        setIconsReady(true)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [mapRef])

  // Viewport-scoped fetch (debounced, zoom-gated, latest-wins) — mirrors ViewportStops.doFetch.
  useEffect(() => {
    const map = mapRef?.getMap()
    if (!map) return
    const doFetch = () => {
      if (map.getZoom() < MIN_ZOOM) {
        abortRef.current?.abort()
        genRef.current++
        setStops([])
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
        .getStopsInViewport(
          { minLat: b.getSouth() - dLat, minLng: b.getWest() - dLng, maxLat: b.getNorth() + dLat, maxLng: b.getEast() + dLng },
          { signal: controller.signal },
        )
        .then((resp) => {
          if (gen === genRef.current) setStops(resp.stops)
        })
        .catch((err: unknown) => {
          if (err instanceof Error && (err.name === 'AbortError' || err.message.includes('abort'))) return
          if (gen === genRef.current) setStops([])
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

  const data = useMemo(() => buildData(stops), [stops])
  if (!iconsReady) return null

  const sel = selectedAtcoCode ?? ''
  const isSel: ExpressionSpecification = ['==', ['get', 'id'], sel]
  const isRail: ExpressionSpecification = ['==', ['get', 'kind'], 'rail']
  // Rail bigger than bus; selected bigger again. (icon source is 44px @2x → ~22px at size 1.)
  const iconSize: ExpressionSpecification = [
    'case',
    isSel,
    ['case', isRail, 1.4, 1.0],
    ['case', isRail, 1.1, 0.7],
  ]

  return (
    <Source id="stops" type="geojson" data={data}>
      {/* Selection halo — a blue ring under the selected stop's icon (the open board's stop). Filtered to
          the selected id; sel === '' matches nothing, so it disappears when nothing is selected. */}
      <Layer
        id="stops-selected"
        type="circle"
        filter={isSel}
        paint={{
          'circle-radius': ['case', isRail, 22, 15],
          'circle-color': '#1565c0',
          'circle-opacity': 0.16,
          'circle-stroke-color': '#1565c0',
          'circle-stroke-width': 2.5,
          'circle-stroke-opacity': 0.85,
        }}
      />
      <Layer
        id={STOPS_LAYER}
        type="symbol"
        layout={{
          'icon-image': ['case', isRail, 'rail-stop', 'bus-stop'],
          'icon-size': iconSize,
          'icon-allow-overlap': true,
          'symbol-sort-key': ['case', isSel, 0, 1], // selected sorts first → drawn on top
        }}
      />
      {/* Stand-count badge on collapsed groups (count is '' for singles → filtered out): a small white
          circle pinned to the icon's top-right, then the number on top. translate (px) and offset (em,
          text-size 12 ⇒ ~10px) are matched so circle + number line up; viewport-anchored to stay put. */}
      <Layer
        id="stops-count-bg"
        type="circle"
        filter={['!=', ['get', 'count'], '']}
        paint={{
          'circle-color': '#ffffff',
          'circle-radius': 8.5,
          'circle-stroke-color': '#cbd5e1',
          'circle-stroke-width': 1.5,
          'circle-translate': [10, -10],
          'circle-translate-anchor': 'viewport',
        }}
      />
      <Layer
        id="stops-count"
        type="symbol"
        filter={['!=', ['get', 'count'], '']}
        layout={{
          'text-field': ['get', 'count'],
          'text-font': ['Noto Sans Medium'],
          'text-size': 12,
          'text-offset': [0.83, -0.83],
          'text-allow-overlap': true,
          'text-ignore-placement': true,
        }}
        paint={{ 'text-color': '#0f172a' }}
      />
    </Source>
  )
}
