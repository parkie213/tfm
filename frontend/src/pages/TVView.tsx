import { useEffect, useMemo, useRef, useState } from 'react'
import type { Departure } from '../proto/tfm/v1/boards_pb'
import type { Stop } from '../proto/tfm/v1/stops_pb'
import { useFlipReorder } from '../hooks/useFlipReorder'
import { TfmLogo } from '../components/TfmLogo'
import { ShopMapGL } from '../components/mapgl/ShopMapGL'
import { useTrainDepartures, hhmmSecs } from '../hooks/useDepartures'
import {
  useCombinedBusDepartures,
  metresBetween,
  type UseCombinedBusDeparturesResult,
} from '../hooks/useTownCentreBuses'
import { standCode } from '../lib/stopGrouping'
import { trainStatus } from '../lib/trainStatus'
import { useKioskStops } from '../hooks/useKioskStops'
import { KioskStopPicker } from '../components/KioskStopPicker'
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
  stops,
  busFeed,
}: {
  now: Date
  stops: Stop[]
  busFeed: UseCombinedBusDeparturesResult
}) {
  const { departures, error, unimplemented, loading } = busFeed

  const isLoading = loading && departures.length === 0
  const hardError = error

  // Show each boarding stop's indicator alongside its name, including opposite-direction stops.
  const indicatorByAtco = useMemo(() => {
    const m = new Map<string, string>()
    for (const s of stops) {
      const code = standCode(s.indicator)
      if (code) m.set(s.atcoCode, code)
    }
    return m
  }, [stops])
  const subtitle = stops.length === 1 ? `${stops[0].name}${stops[0].indicator ? ` · ${stops[0].indicator}` : ''}`
    : `${stops.length} selected stops`

  // Stable per-service identity so a row keeps its DOM node when the live time reorders the board —
  // both for React's reconciliation and the FLIP slide. Uses the server's trip identity.
  const rowKey = (d: Departure, atcoCode: string, index: number) =>
    d.serviceId || `${atcoCode}|${d.line}|${d.destination}|${d.scheduledTime}|${index}`
  const rowsRef = useRef<HTMLDivElement>(null)
  useFlipReorder(rowsRef, departures.map(({ departure, atcoCode }, index) => rowKey(departure, atcoCode, index)))

  return (
    <section className={styles.board}>
      <BoardHeader icon="🚌" title="Buses" subtitle={subtitle} />
      {unimplemented && <EmptyState>Bus timetables aren&rsquo;t enabled on this server</EmptyState>}
      {!unimplemented && hardError && <EmptyState>Departures unavailable</EmptyState>}
      {!unimplemented && !hardError && isLoading && <EmptyState>Loading departures…</EmptyState>}
      {!unimplemented && !hardError && !isLoading && departures.length === 0 && (
        <EmptyState>No buses due from nearby stops right now</EmptyState>
      )}
      {busFeed.unavailableAtcoCodes.length > 0 && <p className={styles.partialNotice} role="status">
        Some selected stops are unavailable. Showing departures from the remaining stops.
      </p>}
      <div className={styles.rows} ref={rowsRef}>
        {departures.map(({ departure: d, stopName, atcoCode }, index) => {
          const stand = indicatorByAtco.get(atcoCode)
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
              key={rowKey(d, atcoCode, index)}
              data-flip-key={rowKey(d, atcoCode, index)}
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
  const { selectedStops, saveStops } = useKioskStops(location)
  const [editingStops, setEditingStops] = useState(false)
  const clock = useClock()
  const now = useRelativeTime()

  const busFeed = useCombinedBusDepartures(selectedStops?.map((stop) => stop.atcoCode) ?? [])

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
      ) : selectedStops === null || editingStops ? (
        <KioskStopPicker key={`${location.lat},${location.lng}`} location={location} selected={selectedStops}
          onSave={(stops) => { saveStops(stops); setEditingStops(false) }}
          onCancel={() => setEditingStops(false)}
          onChangeLocation={() => { clear(); setEditingStops(false) }} />
      ) : (
        <main className={styles.grid}>
          <section className={styles.mapCard}>
            <ShopMapGL shop={location} selectedStops={selectedStops} className={styles.map} />
            <div className={styles.youAreHere}>
              <span className={styles.youAreHereLabel}>You are here</span>
              <span className={styles.youAreHereAddr}>{location.label}</span>
              {(() => {
                const station = walkInfo(location, MELTON_STATION.lat, MELTON_STATION.lng)
                return (
                  <div className={styles.walkRows}>
                    <span className={styles.walkRow}>
                      <span className={styles.walkIcon} aria-hidden="true">🚆</span>
                      <span className={styles.walkDest}>Station</span>
                      <span className={styles.walkTime}>{station.mins} min walk</span>
                      <span className={styles.walkDist}>{station.dist}</span>
                    </span>
                    {selectedStops.map((stop) => {
                      const walk = walkInfo(location, stop.lat, stop.lng)
                      return (
                        <span className={styles.walkRow} key={stop.atcoCode}>
                          <span className={styles.walkIcon} aria-hidden="true">🚌</span>
                          <span className={styles.walkDest}>{stop.name}{stop.indicator ? ` · ${stop.indicator}` : ''}</span>
                          <span className={styles.walkTime}>{walk.mins} min walk</span>
                          <span className={styles.walkDist}>{walk.dist}</span>
                        </span>
                      )
                    })}
                  </div>
                )
              })()}
              <button className={styles.changeLink} onClick={() => setEditingStops(true)} type="button">
                Change bus stops
              </button>
              <button className={styles.changeLink} onClick={clear} type="button">
                Change location
              </button>
            </div>
          </section>
          <BusBoard now={now} stops={selectedStops} busFeed={busFeed} />
          <TrainBoard now={now} />
        </main>
      )}
    </div>
  )
}
