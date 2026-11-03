import { useRef, useState } from 'react'
import type { RouteLegendItem } from '../lib/routeLegend'
import styles from './RouteLegend.module.css'

interface Props {
  routes: RouteLegendItem[]
  selectedRouteKey: string | null
  onSelect: (route: { shortName: string; longName: string; operator: string; color: string }) => void
}

/**
 * Subtle colour key for the "all routes" overlay: the routes currently in view, each with its map
 * colour and number. Collapsed to a "Routes (N)" pill by default so a busy area doesn't bury the map
 * under a tall column; tap the pill to expand the full list and again to collapse it. Each chip is
 * only as wide as the route numbers need; clicking one highlights it on the map. Hidden when no routes
 * are drawn (overlay off / zoomed out).
 *
 * Once expanded and a chip is clicked (so the legend has focus), ↑/↓ step through the routes — each
 * highlighted on the map in turn — wrapping at the ends. Focus follows the selection so arrows keep
 * stepping (and the chip scrolls into view); keeping focus in the legend also stops the arrows from
 * panning the map.
 */
export function RouteLegend({ routes, selectedRouteKey, onSelect }: Props) {
  const listRef = useRef<HTMLUListElement>(null)
  const [expanded, setExpanded] = useState(false)
  if (routes.length === 0) return null

  const emit = (r: RouteLegendItem) =>
    onSelect({ shortName: r.shortName, longName: r.longName, operator: r.operator, color: r.color })

  const step = (dir: 1 | -1) => {
    const n = routes.length
    const cur = routes.findIndex((r) => r.shortName === selectedRouteKey)
    // From no selection, ↓ starts at the top and ↑ at the bottom; otherwise wrap around the list.
    const next = cur === -1 ? (dir === 1 ? 0 : n - 1) : (cur + dir + n) % n
    const r = routes[next]
    emit(r)
    listRef.current
      ?.querySelector<HTMLButtonElement>(`[data-route="${CSS.escape(r.shortName)}"]`)
      ?.focus()
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLUListElement>) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); step(1) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); step(-1) }
  }

  return (
    <div className={`${styles.legend} glass-panel`}>
      <button
        type="button"
        className={styles.header}
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        title={expanded ? 'Hide routes' : 'Show routes'}
      >
        <span className={styles.hamburger} aria-hidden>≡</span>
        <span className={styles.headerLabel}>Routes</span>
        <span className={styles.count}>{routes.length}</span>
      </button>
      {expanded && (
        <ul ref={listRef} className={styles.list} onKeyDown={onKeyDown}>
          {routes.map((r) => {
            const selected = r.shortName === selectedRouteKey
            return (
              <li key={r.shortName}>
                <button
                  data-route={r.shortName}
                  className={`${styles.item} ${selected ? styles.itemSelected : ''}`}
                  onClick={() => emit(r)}
                  title={r.longName}
                >
                  <span className={styles.swatch} style={{ background: r.color }} />
                  <span className={styles.num}>{r.shortName}</span>
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
