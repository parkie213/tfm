import { useEffect, useMemo, useRef, useState } from 'react'
import type { Departure } from '../proto/tfm/v1/boards_pb'
import type { Stop } from '../proto/tfm/v1/stops_pb'
import { useFlipReorder } from '../hooks/useFlipReorder'
import { TfmLogo } from '../components/TfmLogo'
import { ShopMapGL } from '../components/mapgl/ShopMapGL'
import { useTrainDepartures, hhmmSecs } from '../hooks/useDepartures'
import {
  useNearbyStops,
  useCombinedBusDepartures,
  metresBetween,
  type UseNearbyStopsResult,
  type UseCombinedBusDeparturesResult,
} from '../hooks/useTownCentreBuses'
import { standCode, isBayCode } from '../lib/stopGrouping'
import { useShopLocation, geocodeAddress, GeocodeError, type ShopLocation } from '../hooks/useShopLocation'
import { useRelativeTime, relativeTime } from '../hooks/useRelativeTime'
import { MELTON_STATION } from '../constants/stops'
import styles from './TVView.module.css'

// ---- walking distance/time (crow-flies × road detour factor, ~4.8 km/h) ------

const WALK_DETOUR = 1.35 // streets aren't straight lines
const WALK_M_PER_MIN = 80 // ~4.8 km/h

function walkInfo(from: ShopLocation, toLat: number, toLng: number): { dist: string; mins: number } {
  const m = metresBetween(from.lat, from.lng, toLat, toLng) * WALK_DETOUR
  const dist = m < 950 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`
  return { dist, mins: Math.max(1, Math.round(m / WALK_M_PER_MIN)) }
}

// Point at the main stop of the nearby cluster: rank its stops by how many services depart from them
// (busiest = "largest"), tie-broken by proximity, and fall back to the nearest stop.
function busiestNearbyStop(
  from: ShopLocation,
  stops: Stop[],
  departures: { atcoCode: string }[],
): Stop | null {
  if (stops.length === 0) return null
  const counts = new Map<string, number>()
  for (const d of departures) counts.set(d.atcoCode, (counts.get(d.atcoCode) ?? 0) + 1)
  return [...stops]
    .map((s) => ({
      s,
      count: counts.get(s.atcoCode) ?? 0,
      dist: metresBetween(from.lat, from.lng, s.lat, s.lng),
    }))
    .sort((a, b) => b.count - a.count || a.dist - b.dist)[0].s
}

// ---- header clock (ticks on the second; TV displays expect a live clock) -----

function useClock(): Date {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(id)
  }, [])
  return now
}

// ---- punctuality helpers (mirrors DepartureRow's bus logic, TV-sized) --------

function busPunctuality(d: Departure): { text: string; tone: 'on' | 'late' | 'early' | 'idle' } | null {
  if (d.noGps) return { text: 'no GPS', tone: 'idle' }
  if (!d.isLive) return null
  if (d.status === 'Not yet departed') return { text: 'not yet departed', tone: 'idle' }
  const m = d.delayMinutes
  if (m >= 2) return { text: `${m} min late`, tone: 'late' }
  if (m <= -2) return { text: `${Math.abs(m)} min early`, tone: 'early' }
  return { text: 'on time', tone: 'on' }
}

function trainStatus(d: Departure): { text: string; tone: 'on' | 'late' | 'cancelled' } {
  const s = d.status.toLowerCase()
  if (s === 'cancelled') return { text: 'Cancelled', tone: 'cancelled' }
  if (d.delayMinutes > 0) return { text: `${d.delayMinutes} min late`, tone: 'late' }
  return { text: 'On time', tone: 'on' }
}

const toneClass: Record<string, string> = {
  on: styles.pillOn,
  late: styles.pillLate,
  early: styles.pillEarly,
  idle: styles.pillIdle,
  cancelled: styles.pillCancelled,
}

// This board is Melton-centric, so a "Melton Mowbray, College of Further Education" destination wastes
// the whole column on the town name (and the chop hides the useful bit). Strip a leading "Melton
// Mowbray" prefix only — other towns ("Loughborough, …", "Nottingham, …") stay intact.
function shortDest(dest: string): string {
  if (!dest) return '—'
  const stripped = dest.replace(/^melton mowbray\s*[,–-]?\s*/i, '').trim()
  return stripped || dest
}

// ---- address-entry prompt (shown once, until the shop pins itself) -----------

function AddressPrompt({ onResolved }: { onResolved: (q: string) => Promise<void> }) {
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await onResolved(value)
    } catch (err) {
      setError(err instanceof GeocodeError ? err.message : 'Something went wrong — try again')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={styles.promptOverlay}>
      <form className={styles.promptCard} onSubmit={submit}>
        <TfmLogo size={56} color="var(--brand)" amplitude={0.14} />
        <h1 className={styles.promptTitle}>Set up this display</h1>
        <p className={styles.promptBody}>
          Enter this shop&rsquo;s address or postcode to drop the &ldquo;You are here&rdquo; pin. You only
          need to do this once.
        </p>
        <input
          className={styles.promptInput}
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="e.g. 12 High Street, Melton Mowbray  ·  LE13 1XX"
          autoFocus
          disabled={busy}
        />
        {error && <div className={styles.promptError}>{error}</div>}
        <button className={styles.promptButton} type="submit" disabled={busy || !value.trim()}>
          {busy ? 'Locating…' : 'Place pin'}
        </button>
      </form>
    </div>
  )
}

// ---- boards ------------------------------------------------------------------

function BoardHeader({ icon, title, subtitle }: { icon: string; title: string; subtitle: string }) {
  return (
    <div className={styles.boardHead}>
      <span className={styles.boardIcon} aria-hidden="true">
        {icon}
      </span>
      <div className={styles.boardHeadText}>
        <div className={styles.boardTitle}>{title}</div>
        <div className={styles.boardSub}>{subtitle}</div>
      </div>
      <span className={styles.liveDot} aria-label="live">
        <span className={styles.liveDotPing} />
        <span className={styles.liveDotCore} />
      </span>
    </div>
  )
}

function EmptyState({ children }: { children: React.ReactNode }) {
  return <div className={styles.empty}>{children}</div>
}

function TrainBoard({ now }: { now: Date }) {
  const { data, error, loading } = useTrainDepartures()
  // Order by scheduled time, matching the in-app station board: a delayed service keeps its booked slot
  // (its struck-through scheduled time still sorts it) instead of sliding down to its live expected slot.
  const rows = useMemo(
    () =>
      [...(data?.departures ?? [])].sort(
        (a, b) => hhmmSecs(a.scheduledTime) - hhmmSecs(b.scheduledTime),
      ),
    [data],
  )

  return (
    <section className={styles.board}>
      <BoardHeader icon="🚆" title="Trains" subtitle={data?.stationName || MELTON_STATION.name} />
      {error && <EmptyState>Departures unavailable</EmptyState>}
      {!error && loading && rows.length === 0 && <EmptyState>Loading departures…</EmptyState>}
      {!error && !loading && rows.length === 0 && <EmptyState>No trains in the next few hours</EmptyState>}
      <div className={styles.rows}>
        {rows.slice(0, 8).map((d, i) => {
          const st = trainStatus(d)
          const expectedDiffers =
            /^\d{1,2}:\d{2}$/.test(d.expectedTime) && d.expectedTime !== d.scheduledTime
          const rel = relativeTime(d.expectedTime, now) ?? relativeTime(d.scheduledTime, now)
          return (
            <div className={styles.row} key={d.serviceId || `${d.scheduledTime}-${i}`}>
              <div className={styles.timeCell}>
                <span className={`${styles.time} ${expectedDiffers ? styles.timeStruck : ''}`}>
                  {d.scheduledTime}
                </span>
                {expectedDiffers && <span className={styles.timeExpected}>{d.expectedTime}</span>}
              </div>
              <div className={styles.destCell}>
                <span className={styles.dest}>{d.destination || '—'}</span>
                <span className={styles.meta}>
                  {d.platformOrBay ? `Platform ${d.platformOrBay}` : 'Platform —'}
                  {d.operator ? ` · ${d.operator}` : ''}
                </span>
              </div>
              <div className={styles.statusCell}>
                <span className={`${styles.pill} ${toneClass[st.tone]}`}>{st.text}</span>
                {rel && <span className={styles.rel}>{rel.label}</span>}
              </div>
            </div>
          )
        })}
      </div>
    </section>
  )
}

function BusBoard({
  now,
  nearbyStops,
  busFeed,
}: {
  now: Date
  nearbyStops: UseNearbyStopsResult
  busFeed: UseCombinedBusDeparturesResult
}) {
  const { stops, loading: stopsLoading, error: stopsError } = nearbyStops
  const { departures, error, unimplemented, loading } = busFeed

  const isLoading = stopsLoading || (loading && departures.length === 0)
  const hardError = stopsError || error

  // Stand (NaPTAN indicator) per contributing stop, joined to a departure by its atcoCode — the same
  // source the main app's combined bus board uses. We only surface a per-row stand when the visible
  // services actually leave from more than one stand; if they all share one, a column of identical "S2"
  // reads as broken, so we state that single stand once in the header instead. (Mirrors BoardPanel.)
  const indicatorByAtco = useMemo(() => {
    const m = new Map<string, string>()
    for (const s of stops) {
      const code = standCode(s.indicator)
      if (code) m.set(s.atcoCode, code)
    }
    return m
  }, [stops])
  const distinctStands = useMemo(() => {
    const set = new Set<string>()
    for (const { atcoCode } of departures) {
      const ind = indicatorByAtco.get(atcoCode)
      if (ind) set.add(ind)
    }
    return [...set]
  }, [departures, indicatorByAtco])
  const showStandPerRow = distinctStands.length > 1
  // When every visible service shares one stand, state it once in the header. Prefix "Stand" only for
  // numbered bays (S2) — not relation indicators (opp/outside), where "Stand outside" would read wrong.
  const loneStand = distinctStands.length === 1 ? distinctStands[0] : null
  // Name the cluster after its nearest stop (stops arrive nearest-first), e.g. "College of Further Education".
  const place = stops[0]?.name || 'Nearby stops'
  const subtitle = loneStand ? `${place} · ${isBayCode(loneStand) ? 'Stand ' : ''}${loneStand}` : place

  // Stable per-service identity so a row keeps its DOM node when the live time reorders the board —
  // both for React's reconciliation and the FLIP slide. Mirrors the hook's trip-dedup key.
  const rowKey = (d: Departure) => d.serviceId || `${d.line}|${d.destination}|${d.scheduledTime}`
  const rowsRef = useRef<HTMLDivElement>(null)
  useFlipReorder(rowsRef, departures.map(({ departure }) => rowKey(departure)))

  return (
    <section className={styles.board}>
      <BoardHeader icon="🚌" title="Buses" subtitle={subtitle} />
      {unimplemented && <EmptyState>Bus timetables aren&rsquo;t enabled on this server</EmptyState>}
      {!unimplemented && hardError && <EmptyState>Departures unavailable</EmptyState>}
      {!unimplemented && !hardError && isLoading && <EmptyState>Loading departures…</EmptyState>}
      {!unimplemented && !hardError && !isLoading && departures.length === 0 && (
        <EmptyState>No buses due from nearby stops right now</EmptyState>
      )}
      <div className={styles.rows} ref={rowsRef}>
        {departures.map(({ departure: d, stopName, atcoCode }) => {
          const stand = showStandPerRow ? indicatorByAtco.get(atcoCode) : undefined
          const punct = busPunctuality(d)
          const expectedDiffers =
            d.isLive &&
            /^\d{1,2}:\d{2}$/.test(d.expectedTime) &&
            d.expectedTime !== d.scheduledTime &&
            Math.abs(d.delayMinutes) >= 2
          const rel = relativeTime(d.isLive ? d.expectedTime : d.scheduledTime, now) ??
            relativeTime(d.scheduledTime, now)
          const isLate = d.isLive && d.delayMinutes >= 2
          return (
            <div
              className={`${styles.row} ${isLate ? styles.rowLate : ''}`}
              key={rowKey(d)}
              data-flip-key={rowKey(d)}
            >
              <div className={styles.timeCell}>
                <span className={`${styles.time} ${expectedDiffers ? styles.timeStruck : ''}`}>
                  {d.scheduledTime}
                </span>
                {expectedDiffers && <span className={styles.timeExpected}>{d.expectedTime}</span>}
              </div>
              <div className={styles.lineCell}>
                <span className={styles.lineBadge}>{d.line || '—'}</span>
              </div>
              <div className={styles.destCell}>
                <span className={styles.dest}>{shortDest(d.destination)}</span>
                <span className={styles.meta}>
                  {stand && <span className={styles.standPill}>{stand}</span>}
                  {stopName}
                </span>
              </div>
              <div className={styles.statusCell}>
                {punct && <span className={`${styles.pill} ${toneClass[punct.tone]}`}>{punct.text}</span>}
                {rel && <span className={styles.rel}>{rel.label}</span>}
              </div>
            </div>
          )
        })}
      </div>
    </section>
  )
}

// ---- page --------------------------------------------------------------------

export default function TVView() {
  const { location, setLocation, clear } = useShopLocation()
  const clock = useClock()
  const now = useRelativeTime()

  // Owned here (not in BusBoard) so the walk-distance card and the board share one polled feed.
  const nearbyStops = useNearbyStops(location)
  const busFeed = useCombinedBusDepartures(nearbyStops.atcoCodes)
  const majorStop = useMemo(
    () => (location ? busiestNearbyStop(location, nearbyStops.stops, busFeed.departures) : null),
    [location, nearbyStops.stops, busFeed.departures],
  )

  const dateLabel = clock.toLocaleDateString('en-GB', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  })
  const timeLabel = clock.toLocaleTimeString('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })

  async function handleAddress(query: string) {
    const loc = await geocodeAddress(query)
    setLocation(loc)
  }

  return (
    <div className={styles.tv}>
      <header className={styles.header}>
        <div className={styles.brand}>
          <TfmLogo size={30} color="var(--brand)" amplitude={0.14} />
          <div>
            <div className={styles.brandName}>Transport for Melton</div>
            <div className={styles.brandTag}>powered by pies 🥧</div>
          </div>
        </div>
        <div className={styles.headerRight}>
          <div className={styles.clock}>
            <div className={styles.clockTime}>{timeLabel}</div>
            <div className={styles.clockDate}>{dateLabel}</div>
          </div>
          {/* Leave the kiosk view — drops ?view=tv and returns to the map app. */}
          <a className={styles.exitButton} href={window.location.pathname} title="Exit TV display">
            ✕ Exit
          </a>
        </div>
      </header>

      {!location ? (
        <AddressPrompt onResolved={handleAddress} />
      ) : (
        <main className={styles.grid}>
          <section className={styles.mapCard}>
            <ShopMapGL shop={location} className={styles.map} />
            <div className={styles.youAreHere}>
              <span className={styles.youAreHereLabel}>You are here</span>
              <span className={styles.youAreHereAddr}>{location.label}</span>
              {(() => {
                const station = walkInfo(location, MELTON_STATION.lat, MELTON_STATION.lng)
                const stop = majorStop ? walkInfo(location, majorStop.lat, majorStop.lng) : null
                return (
                  <div className={styles.walkRows}>
                    <span className={styles.walkRow}>
                      <span className={styles.walkIcon} aria-hidden="true">🚆</span>
                      <span className={styles.walkDest}>Station</span>
                      <span className={styles.walkTime}>{station.mins} min walk</span>
                      <span className={styles.walkDist}>{station.dist}</span>
                    </span>
                    {majorStop && stop && (
                      <span className={styles.walkRow}>
                        <span className={styles.walkIcon} aria-hidden="true">🚌</span>
                        <span className={styles.walkDest}>{majorStop.name}</span>
                        <span className={styles.walkTime}>{stop.mins} min walk</span>
                        <span className={styles.walkDist}>{stop.dist}</span>
                      </span>
                    )}
                  </div>
                )
              })()}
              <button className={styles.changeLink} onClick={clear} type="button">
                Change location
              </button>
            </div>
          </section>
          <BusBoard now={now} nearbyStops={nearbyStops} busFeed={busFeed} />
          <TrainBoard now={now} />
        </main>
      )}
    </div>
  )
}
