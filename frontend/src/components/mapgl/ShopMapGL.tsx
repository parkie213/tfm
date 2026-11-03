import { useEffect, useRef, useState, type CSSProperties } from 'react'
import maplibregl from 'maplibre-gl'
import Map, { Marker } from 'react-map-gl/maplibre'
import type { MapRef, MapEvent } from 'react-map-gl/maplibre'
import 'maplibre-gl/dist/maplibre-gl.css'
import { BASEMAP_STYLE } from './basemap'
import { MELTON_STATION } from '../../constants/stops'
import type { Stop } from '../../proto/tfm/v1/stops_pb'
import type { ShopLocation } from '../../hooks/useShopLocation'
import { useBusVehicles, type Bbox } from '../../hooks/useBusVehicles'
import { BusRoutesGL } from './BusRoutesGL'
import { firstLabelLayerId } from './layerOrder'
import { StopsGL } from './StopsGL'
import { BusMarkersGL } from './BusMarkersGL'

/** Non-interactive kiosk map, centred on the shop, with nearby stops, routes, and live buses. */

const BUS_VIEW_MIN_ZOOM = 14
// Street-level: the pin and the stops outside it, a few hundred metres either way.
const SHOP_ZOOM = 16


const PIN_SVG =
  '<svg viewBox="0 0 24 24" width="20" height="20" fill="#fff" aria-hidden="true"><path d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/></svg>'
const TRAIN_SVG =
  '<svg viewBox="0 0 24 24" width="15" height="15" fill="#fff" aria-hidden="true"><path d="M12 2c-4 0-8 .5-8 4v9.5A3.5 3.5 0 0 0 7.5 19L6 20.5v.5h12v-.5L16.5 19a3.5 3.5 0 0 0 3.5-3.5V6c0-3.5-4-4-8-4zM7.5 17A1.5 1.5 0 1 1 9 15.5 1.5 1.5 0 0 1 7.5 17zM11 10H6V6h5v4zm2 0V6h5v4h-5zm3.5 7a1.5 1.5 0 1 1 1.5-1.5 1.5 1.5 0 0 1-1.5 1.5z"/></svg>'

function pinStyle(size: number, bg: string, pulse = false): CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: size,
    height: size,
    borderRadius: '50%',
    background: bg,
    boxShadow: '0 2px 8px rgba(0,0,0,.45)',
    border: '2px solid rgba(255,255,255,.9)',
    animation: pulse ? 'tfmShopPulse 2.2s ease-out infinite' : undefined,
  }
}

export function ShopMapGL({ shop, selectedStops, className }: { shop: ShopLocation; selectedStops: Stop[]; className?: string }) {
  const mapRef = useRef<MapRef | null>(null)
  const [firstLabelId, setFirstLabelId] = useState<string | undefined>(undefined)
  const [busBounds, setBusBounds] = useState<Bbox | null>(null)
  const buses = useBusVehicles(busBounds)

  const fitAndReport = (map: maplibregl.Map) => {
    const bounds = new maplibregl.LngLatBounds([shop.lng, shop.lat], [shop.lng, shop.lat])
    for (const stop of selectedStops) bounds.extend([stop.lng, stop.lat])
    map.fitBounds(bounds, { padding: 45, maxZoom: SHOP_ZOOM, duration: 0 })
    const b = map.getBounds()
    const z = map.getZoom()
    const box: Bbox = { minLat: b.getSouth(), minLng: b.getWest(), maxLat: b.getNorth(), maxLng: b.getEast() }
    setBusBounds(z >= BUS_VIEW_MIN_ZOOM ? box : null)
  }

  const onLoad = (e: MapEvent) => {
    setFirstLabelId(firstLabelLayerId(e.target))
    fitAndReport(e.target)
  }
  // Re-frame when the shop location changes.
  useEffect(() => {
    const m = mapRef.current?.getMap()
    if (m && m.isStyleLoaded()) fitAndReport(m)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shop.lat, shop.lng, selectedStops])

  const live: string[] = []
  if (!buses.unimplemented) live.push(`🚌 ${buses.vehicles.length}`)

  return (
    <div className={className} style={{ position: 'relative', overflow: 'hidden' }}>
      <Map
        ref={mapRef}
        mapStyle={BASEMAP_STYLE}
        interactive={false}
        attributionControl={{ compact: true }}
        onLoad={onLoad}
        initialViewState={{ longitude: shop.lng, latitude: shop.lat, zoom: SHOP_ZOOM }}
        style={{ width: '100%', height: '100%', filter: 'saturate(0.92) brightness(0.97)' }}
      >
        <BusRoutesGL selectedRouteKey={null} firstLabelId={firstLabelId} />
        <StopsGL selectedAtcoCode={null} />
        <BusMarkersGL vehicles={buses.vehicles} />

        <Marker longitude={MELTON_STATION.lng} latitude={MELTON_STATION.lat} anchor="center">
          <div style={pinStyle(30, 'var(--rail, #c0392b)')} dangerouslySetInnerHTML={{ __html: TRAIN_SVG }} />
        </Marker>
        <Marker longitude={shop.lng} latitude={shop.lat} anchor="center">
          <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <div
              style={{
                position: 'absolute',
                bottom: 30,
                whiteSpace: 'nowrap',
                padding: '2px 8px',
                borderRadius: 999,
                background: 'oklch(0.18 0.03 250 / 0.85)',
                color: '#fff',
                font: '700 11px/1.2 var(--font-sans, system-ui)',
                boxShadow: '0 1px 3px rgba(0,0,0,.45)',
              }}
            >
              You are here
            </div>
            <div style={pinStyle(38, 'var(--accent, #d2691e)', true)} dangerouslySetInnerHTML={{ __html: PIN_SVG }} />
          </div>
        </Marker>
      </Map>

      {live.length > 0 && (
        <div
          style={{
            position: 'absolute',
            top: 10,
            left: 10,
            zIndex: 500,
            padding: '0.3rem 0.6rem',
            borderRadius: 999,
            background: 'oklch(0.18 0.03 250 / 0.78)',
            color: 'oklch(0.97 0.01 250)',
            fontSize: '0.85rem',
            fontWeight: 600,
            letterSpacing: '0.02em',
            backdropFilter: 'blur(6px)',
            pointerEvents: 'none',
          }}
        >
          {live.join('  ·  ')} live
        </div>
      )}
    </div>
  )
}
