import { useEffect, useMemo, useRef } from 'react'
import maplibregl from 'maplibre-gl'
import { useMap } from 'react-map-gl/maplibre'
import type { BusVehicle } from '../../proto/tfm/v1/buses_pb'
import { useVehicleAnimation } from '../../hooks/useVehicleAnimation'
import { busesToAnimatable } from '../../hooks/useBusVehicles'

/**
 * Live buses as HTML markers, driven by the SAME useVehicleAnimation (extrapolate / forward-prediction
 * mode) as the Leaflet map. App owns the bus feed; the vehicles are passed in so there's a single
 * stream. Clicking a bus selects its service (App opens the service-detail board). Camera-follow is the
 * only Leaflet-only piece not yet ported.
 */

const BUS_POLL_MS = 12_000

function makeBusBadge(line: string): HTMLDivElement {
  const el = document.createElement('div')
  // No position:relative — it would override maplibre's .maplibregl-marker{position:absolute}, dropping
  // the badge into flow where a width-less flex box stretches full-width. The abs marker is the arrow's
  // containing block anyway.
  el.style.cssText =
    'display:flex;align-items:center;justify-content:center;min-width:24px;height:20px;' +
    'padding:0 5px;background:#0891b2;color:#fff;font:700 11px system-ui,sans-serif;border-radius:5px;' +
    'box-shadow:0 1px 3px rgba(0,0,0,.4);cursor:pointer;will-change:transform'
  el.textContent = line || '•'
  const arrow = document.createElement('div')
  arrow.className = 'bus-badge__arrow' // applyArrowAndOpacity rotates this to the heading
  arrow.textContent = '▲'
  arrow.style.cssText = 'position:absolute;top:-11px;left:50%;margin-left:-6px;width:12px;text-align:center;color:#0891b2;font-size:11px;line-height:1'
  el.appendChild(arrow)
  return el
}

export function BusMarkersGL({ vehicles, onSelectVehicle }: { vehicles: BusVehicle[]; onSelectVehicle?: (id: string) => void }) {
  const map = useMap().current?.getMap()
  const items = useMemo(() => busesToAnimatable(vehicles), [vehicles])
  const { markerRefs } = useVehicleAnimation(items, BUS_POLL_MS, null, { extrapolate: true })
  const markersRef = useRef<Map<string, maplibregl.Marker>>(new Map())
  const onSelRef = useRef(onSelectVehicle)
  onSelRef.current = onSelectVehicle

  useEffect(() => {
    if (!map) return
    const live = new Set(vehicles.map((v) => v.id))
    for (const [id, m] of markersRef.current) {
      if (!live.has(id)) {
        m.remove()
        markersRef.current.delete(id)
        markerRefs.current.delete(id)
      }
    }
    for (const v of vehicles) {
      if (markersRef.current.has(v.id)) continue
      const el = makeBusBadge(v.line)
      el.addEventListener('click', (e) => {
        e.stopPropagation()
        onSelRef.current?.(v.id)
      })
      const lng = v.snapped ? v.snappedLng : v.lng
      const lat = v.snapped ? v.snappedLat : v.lat
      const m = new maplibregl.Marker({ element: el }).setLngLat([lng, lat]).addTo(map)
      markersRef.current.set(v.id, m)
      markerRefs.current.set(v.id, {
        setLatLng: ([la, ln]) => m.setLngLat([ln, la]),
        getElement: () => m.getElement(),
      })
    }
  }, [vehicles, map, markerRefs])

  useEffect(() => {
    const markers = markersRef.current
    const refs = markerRefs.current
    return () => {
      for (const m of markers.values()) m.remove()
      markers.clear()
      refs.clear()
    }
  }, [markerRefs])

  return null
}
