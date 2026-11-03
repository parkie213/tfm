import { useEffect, useRef, useState } from 'react'
import type { Stop, Locality, SearchItem } from '../proto/tfm/v1/stops_pb'
import { client } from '../transport'
import type { SelectedStop } from '../App'
import styles from './StopSearch.module.css'

const DEBOUNCE_MS = 250
const MIN_QUERY_LEN = 3
const LIMIT = 8
const STOP_ZOOM = 16
const PLACE_ZOOM = 15 // a town: close enough to see its stops/routes, not a distant overview

interface Props {
  /** Map-agnostic fly callback — flies the map to the selected stop/place. */
  onFlyTo?: (lat: number, lng: number, zoom: number) => void
  onPick: (stop: SelectedStop) => void
}

/** A dropdown row: either a place (NPTG locality) or a stop. The server returns them pre-interleaved. */
type Item = { kind: 'place'; place: Locality } | { kind: 'stop'; stop: Stop }

/** Map a wire SearchItem (oneof stop|place) to a dropdown Item; null if the oneof is unset. */
function toItem(si: SearchItem): Item | null {
  switch (si.item.case) {
    case 'stop':
      return { kind: 'stop', stop: si.item.value }
    case 'place':
      return { kind: 'place', place: si.item.value }
    default:
      return null
  }
}

/**
 * Collapse the duplicate rail-station rows the backend returns for one physical station — it appears
 * once per NaPTAN type (e.g. RSE *and* RLY for Melton Mowbray, both CRS "MMO"), which renders as two
 * identical "… Rail Station" rows. Keep the first (the server's best-ranked) per CRS; bus stops have
 * no CRS so are never merged, and a CRS is unique per station so this can't merge distinct stations.
 */
function dedupeRailByCrs(items: Item[]): Item[] {
  const seen = new Set<string>()
  return items.filter((it) => {
    if (it.kind !== 'stop' || !it.stop.crs) return true
    if (seen.has(it.stop.crs)) return false
    seen.add(it.stop.crs)
    return true
  })
}

/**
 * Search box with typeahead dropdown.
 *
 * - Debounced 250 ms; sends searchStops once query length >= 3.
 * - The server returns stops and places pre-interleaved by a unified rank (a strongly-matched town
 *   leads, then its station, then buses; a weak place sinks below real stops). Selecting a place flies
 *   the map to the town (no board); selecting a stop flies in and opens its board via onPick.
 * - Latest-wins: stale responses are silently discarded.
 * - Keyboard: ↑/↓ to navigate, Enter to select highlighted, Esc to dismiss.
 */
export function StopSearch({ onFlyTo, onPick }: Props) {
  const flyTo = (lat: number, lng: number, zoom: number) => {
    onFlyTo?.(lat, lng, zoom)
  }
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<Item[]>([])
  const [highlighted, setHighlighted] = useState(-1)
  const [open, setOpen] = useState(false)
  // In-flight (debounce + RPC): drives a "Searching…" row, and lets a settled empty result show a
  // "no matches" row instead of the dropdown silently vanishing.
  const [searching, setSearching] = useState(false)

  const abortRef = useRef<AbortController | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const generationRef = useRef(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)

  // Run a search whenever query changes (debounced).
  useEffect(() => {
    if (debounceRef.current !== null) clearTimeout(debounceRef.current)

    if (query.length < MIN_QUERY_LEN) {
      abortRef.current?.abort()
      setItems([])
      setSearching(false)
      setOpen(false)
      return
    }

    // Open immediately and show progress; the body keeps the previous results visible while a new
    // query is in flight (stale-while-searching), falling back to "Searching…" only with nothing yet.
    setSearching(true)
    setOpen(true)

    debounceRef.current = setTimeout(() => {
      debounceRef.current = null

      const gen = ++generationRef.current
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller

      client
        .searchStops({ query, limit: LIMIT }, { signal: controller.signal })
        .then((response) => {
          if (gen !== generationRef.current) return
          const next = dedupeRailByCrs(
            response.items.map(toItem).filter((it): it is Item => it !== null),
          )
          setItems(next)
          // Pre-highlight the top match so the highlighted row matches what Enter will pick (no
          // "nothing's selected but Enter jumps to result 0" surprise) and aria-activedescendant points
          // somewhere real.
          setHighlighted(next.length > 0 ? 0 : -1)
          setSearching(false)
        })
        .catch((err: unknown) => {
          if (err instanceof Error && (err.name === 'AbortError' || err.message.includes('abort'))) return
          if (gen === generationRef.current) {
            setItems([])
            setSearching(false)
          }
        })
    }, DEBOUNCE_MS)

    return () => {
      if (debounceRef.current !== null) clearTimeout(debounceRef.current)
    }
  }, [query])

  // Cleanup on unmount.
  useEffect(() => {
    return () => {
      abortRef.current?.abort()
      if (debounceRef.current !== null) clearTimeout(debounceRef.current)
    }
  }, [])

  function reset() {
    setQuery('')
    setItems([])
    setOpen(false)
    setHighlighted(-1)
  }

  function pickStop(stop: Stop) {
    const name = stop.indicator ? `${stop.name} (${stop.indicator})` : stop.name
    flyTo(stop.lat, stop.lng, STOP_ZOOM)
    // A rail station's CRS becomes the "Name | CRS" short code and drives the live train board.
    onPick({
      atcoCode: stop.atcoCode,
      name,
      shortName: stop.crs || undefined,
      stopType: stop.stopType,
      crs: stop.crs,
    })
    reset()
  }

  function pickPlace(place: Locality) {
    // If a stop in the same result set shares this place's name (e.g. a village's own stop),
    // prefer flying to that stop at full stop zoom rather than a distant locality overview.
    const named = items
      .find((it) => it.kind === 'stop' && it.stop.name.toLowerCase() === place.name.toLowerCase())
    const namedStop = named?.kind === 'stop' ? named.stop : undefined
    const zoom = namedStop ? STOP_ZOOM : PLACE_ZOOM
    const lat = namedStop ? namedStop.lat : place.lat
    const lng = namedStop ? namedStop.lng : place.lng
    // A place isn't a stop — just take the user there; don't open a board.
    flyTo(lat, lng, zoom)
    reset()
  }

  function pick(item: Item) {
    if (item.kind === 'place') pickPlace(item.place)
    else pickStop(item.stop)
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (!open || items.length === 0) return

    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setHighlighted((h) => Math.min(h + 1, items.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setHighlighted((h) => Math.max(h - 1, 0))
    } else if (e.key === 'Enter') {
      // Only act on an explicit highlight (the top match is pre-highlighted when results arrive), so
      // Enter never silently fires on a row the user can't see is selected.
      if (highlighted >= 0 && items[highlighted]) {
        e.preventDefault()
        pick(items[highlighted])
      }
    } else if (e.key === 'Escape') {
      e.preventDefault()
      setOpen(false)
      setHighlighted(-1)
      inputRef.current?.blur()
    }
  }

  // Scroll the highlighted item into view inside the list.
  useEffect(() => {
    if (highlighted < 0 || !listRef.current) return
    const item = listRef.current.children[highlighted] as HTMLElement | undefined
    item?.scrollIntoView({ block: 'nearest' })
  }, [highlighted])

  function stopTypeTag(stopType: string): string {
    if (stopType.startsWith('B')) return 'bus'
    if (stopType === 'RSE' || stopType === 'RLY') return 'rail'
    if (stopType === 'MET') return 'metro'
    return stopType.toLowerCase()
  }

  return (
    <div className={styles.wrapper}>
      <div className={styles.inputRow}>
        <span className={styles.searchIcon} aria-hidden="true">
          {/* Magnifier SVG — no emoji, consistent with the rest of the app's icon style. */}
          <svg viewBox="0 0 20 20" width="16" height="16" fill="currentColor" aria-hidden="true">
            <path fillRule="evenodd" d="M8 4a4 4 0 100 8 4 4 0 000-8zM2 8a6 6 0 1110.89 3.476l4.817 4.817a1 1 0 01-1.414 1.414l-4.816-4.816A6 6 0 012 8z" clipRule="evenodd" />
          </svg>
        </span>
        <input
          ref={inputRef}
          className={styles.input}
          type="search"
          placeholder="Search stops or places…"
          autoComplete="off"
          spellCheck={false}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => {
            if (items.length > 0) setOpen(true)
          }}
          onBlur={() => {
            // Delay close so a click on a result fires before the list disappears.
            setTimeout(() => setOpen(false), 150)
          }}
          role="combobox"
          aria-label="Search for a bus stop or place"
          aria-autocomplete="list"
          aria-expanded={open}
          aria-controls="stop-search-list"
          aria-activedescendant={
            open && highlighted >= 0 ? `stop-search-opt-${highlighted}` : undefined
          }
        />
      </div>

      {open && query.length >= MIN_QUERY_LEN && items.length === 0 && (
        <div className={styles.dropdown} role="status" aria-live="polite">
          <p className={styles.statusRow}>
            {searching ? 'Searching…' : 'No matching stops or places'}
          </p>
        </div>
      )}

      {open && items.length > 0 && (
        <ul
          id="stop-search-list"
          role="listbox"
          ref={listRef}
          className={styles.dropdown}
        >
          {items.map((item, idx) => {
            const highlightedClass = idx === highlighted ? styles.resultHighlighted : ''
            const key = item.kind === 'place' ? `p:${item.place.code}` : `s:${item.stop.atcoCode}`
            return (
              <li
                key={key}
                id={`stop-search-opt-${idx}`}
                role="option"
                aria-selected={idx === highlighted}
                className={`${styles.result} ${highlightedClass}`}
                onMouseDown={(e) => {
                  // mousedown fires before blur; prevent the input losing focus before we handle it.
                  e.preventDefault()
                  pick(item)
                }}
              >
                {item.kind === 'place' ? (
                  <>
                    <span className={styles.resultName}>{item.place.name}</span>
                    <span className={styles.resultMeta}>
                      {item.place.qualifier && (
                        <span className={styles.resultLocality}>{item.place.qualifier}</span>
                      )}
                      <span className={`${styles.resultTag} ${styles.resultTagPlace}`}>place</span>
                    </span>
                  </>
                ) : (
                  <>
                    <span className={styles.resultName}>
                      {item.stop.name}
                      {item.stop.indicator ? (
                        <span className={styles.resultIndicator}> {item.stop.indicator}</span>
                      ) : null}
                    </span>
                    <span className={styles.resultMeta}>
                      {item.stop.locality && (
                        <span className={styles.resultLocality}>{item.stop.locality}</span>
                      )}
                      <span className={styles.resultTag}>{stopTypeTag(item.stop.stopType)}</span>
                    </span>
                  </>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
