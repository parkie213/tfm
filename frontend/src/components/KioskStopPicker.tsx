import { useMemo, useState } from 'react'
import type { Stop } from '../proto/tfm/v1/stops_pb'
import type { ShopLocation } from '../hooks/useShopLocation'
import { metresBetween, useNearbyStops } from '../hooks/useTownCentreBuses'
import styles from '../pages/TVView.module.css'

/** The venue chooses its boarding stops; proximity supplies candidates, not an automatic group. */
export function KioskStopPicker({ location, selected, onSave, onCancel, onChangeLocation }: {
  location: ShopLocation
  selected: Stop[] | null
  onSave: (stops: Stop[]) => void
  onCancel: () => void
  onChangeLocation: () => void
}) {
  const [radius, setRadius] = useState(800)
  const nearby = useNearbyStops(location, radius)
  const [chosen, setChosen] = useState<string[]>(() => selected?.map((s) => s.atcoCode) ?? [])
  const candidates = useMemo(() => {
    const all = new Map(nearby.stops.map((s) => [s.atcoCode, s]))
    for (const stop of selected ?? []) if (!all.has(stop.atcoCode)) all.set(stop.atcoCode, stop)
    return [...all.values()]
  }, [nearby.stops, selected])
  function toggle(code: string) {
    setChosen((current) => current.includes(code) ? current.filter((c) => c !== code) : [...current, code])
  }
  function save() {
    const byCode = new Map(candidates.map((s) => [s.atcoCode, s]))
    const stops = chosen.flatMap((code) => { const stop = byCode.get(code); return stop ? [stop] : [] })
    if (stops.length) onSave(stops)
  }
  return (
    <div className={styles.promptOverlay}>
      <section className={`${styles.promptCard} ${styles.stopPicker}`} aria-labelledby="stop-picker-title">
        <h1 id="stop-picker-title" className={styles.promptTitle}>Choose bus stops</h1>
        <p className={styles.promptBody}>Select up to 10 stops to show at {location.label}.
          Each departure will show where to board.</p>
        {nearby.loading && <p role="status">Finding nearby stops…</p>}
        {nearby.error && <p className={styles.promptError} role="alert">{nearby.error}</p>}
        {!nearby.loading && !nearby.error && candidates.length === 0 && <p>No bus stops found in this area.</p>}
        <div className={styles.stopChoices}>
          {candidates.map((stop) => {
            const checked = chosen.includes(stop.atcoCode)
            const distance = Math.round(metresBetween(location.lat, location.lng, stop.lat, stop.lng))
            return (
              <label className={styles.stopChoice} key={stop.atcoCode}>
                <input type="checkbox" checked={checked} disabled={!checked && chosen.length >= 10}
                  onChange={() => toggle(stop.atcoCode)} />
                <span><strong>{stop.name}</strong>
                  <span className={styles.stopDetail}>{stop.indicator || stop.atcoCode} · {distance} m away</span>
                </span>
              </label>
            )
          })}
        </div>
        {radius === 800 && <button type="button" className={styles.changeLink} onClick={() => setRadius(1600)}>
          Look farther away (1.6 km)
        </button>}
        <button type="button" className={styles.promptButton} disabled={!chosen.length || nearby.loading || !!nearby.error}
          onClick={save}>Use {chosen.length || ''} selected {chosen.length === 1 ? 'stop' : 'stops'}</button>
        <div className={styles.setupActions}>
          {selected && <button type="button" className={styles.changeLink} onClick={onCancel}>Cancel</button>}
          <button type="button" className={styles.changeLink} onClick={onChangeLocation}>Change location</button>
        </div>
      </section>
    </div>
  )
}
