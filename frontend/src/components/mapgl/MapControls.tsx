import { useControl } from 'react-map-gl/maplibre'
import type { ControlPosition } from 'react-map-gl/maplibre'
import type { IControl, Map as MlMap } from 'maplibre-gl'

// Glyphs mirror the Leaflet controls (StationMap RECENTER_SVG / SHARE_SVG / CHECK_SVG).
const RECENTER_SVG =
  '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" ' +
  'stroke-width="2" stroke-linecap="round" aria-hidden="true">' +
  '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3"/></svg>'
const SHARE_SVG =
  '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" ' +
  'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
  '<path d="M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/>' +
  '<path d="M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>'
const CHECK_SVG =
  '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" ' +
  'stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
  '<path d="M5 13l4 4L19 7"/></svg>'

/** A single-button MapLibre IControl that sits in the standard ctrl-group chrome. */
class ButtonControl implements IControl {
  private container?: HTMLDivElement
  constructor(
    private cfg: { html: string; title: string; onClick: (map: MlMap, btn: HTMLButtonElement) => void },
  ) {}
  onAdd(map: MlMap) {
    const c = document.createElement('div')
    c.className = 'maplibregl-ctrl maplibregl-ctrl-group'
    const b = document.createElement('button')
    b.type = 'button'
    b.title = this.cfg.title
    b.setAttribute('aria-label', this.cfg.title)
    b.innerHTML = this.cfg.html
    b.style.cssText = 'display:flex;align-items:center;justify-content:center'
    b.addEventListener('click', () => this.cfg.onClick(map, b))
    c.appendChild(b)
    this.container = c
    return c
  }
  onRemove() {
    this.container?.remove()
  }
}

/** Flies the camera back to a fixed centre/zoom (Melton) — the react-leaflet RecenterControl port. */
export function RecenterControl({
  center,
  zoom,
  position = 'bottom-right',
}: {
  center: [number, number] // [lng, lat]
  zoom: number
  position?: ControlPosition
}) {
  useControl(
    () =>
      new ButtonControl({
        html: RECENTER_SVG,
        title: 'Recentre on Melton',
        onClick: (map) => map.flyTo({ center, zoom, duration: 600 }),
      }),
    { position },
  )
  return null
}

/** Copies a deep link to the current view (?lat&lng&zoom) — the ShareViewControl port. */
export function ShareViewControl({ position = 'bottom-right' }: { position?: ControlPosition }) {
  useControl(
    () =>
      new ButtonControl({
        html: SHARE_SVG,
        title: 'Copy a link to this view',
        onClick: (map, btn) => {
          const c = map.getCenter()
          const url =
            `${window.location.origin}${window.location.pathname}` +
            `?lat=${c.lat.toFixed(5)}&lng=${c.lng.toFixed(5)}&zoom=${Math.round(map.getZoom())}`
          const ok = () => {
            btn.innerHTML = CHECK_SVG
            btn.title = 'Link copied'
            setTimeout(() => {
              btn.innerHTML = SHARE_SVG
              btn.title = 'Copy a link to this view'
            }, 1600)
          }
          void navigator.clipboard?.writeText(url).then(ok).catch(() => undefined)
        },
      }),
    { position },
  )
  return null
}
