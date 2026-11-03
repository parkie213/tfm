import type { CSSProperties } from 'react'
import type { PlatformOccupant } from '../proto/tfm/v1/trains_pb'
import type { StationBoardRow } from '../hooks/useDepartures'
import { useStationPlatforms } from '../hooks/useStationPlatforms'
import styles from './StationPlatformBoard.module.css'

/** Loading skeleton: three placeholder rows. */
function PlatformSkeleton() {
  return (
    <div className={styles.skeleton} aria-busy="true" aria-label="Loading platform board">
      {[0, 1, 2].map((i) => (
        <div key={i} className={styles.skeletonRow} />
      ))}
    </div>
  )
}

/** Persistent error state (retrying automatically via the poll loop). */
function PlatformError({ message }: { message: string }) {
  return (
    <div className={styles.error} role="alert">
      <p className={styles.errorTitle}>Could not load platform board</p>
      <p className={styles.errorDetail}>{message}</p>
      <p className={styles.errorHint}>Retrying automatically.</p>
    </div>
  )
}

/**
 * One service on a platform. {@code dest} is the route ("Origin → Destination"), {@code meta} its time
 * and status, and {@code confirmed} marks platforms the Darwin push-port feed has operationally
 * confirmed (a quiet green dot — the absence of it is the normal, unremarkable case, so there is no
 * noisy "expected" badge).
 */
interface DisplayOccupant {
  key: string
  dest: string
  meta: string | null
  confirmed: boolean
  /** Darwin movement shows the service has arrived but not departed — standing here now. */
  atPlatform: boolean
}

/** Live board row → display: "Origin → Destination" with its time and status. */
function fromBoardRow(r: StationBoardRow, confirmed: boolean, atPlatform: boolean): DisplayOccupant {
  const dest =
    r.origin && r.destination ? `${r.origin} → ${r.destination}` : r.destination || r.origin || 'Train'
  const side = r.dep ?? r.arr
  let meta: string | null = null
  if (side) {
    const reTimed = /^\d{1,2}:\d{2}$/.test(side.expected) && side.expected !== side.scheduled
    meta = reTimed ? `${side.scheduled} → ${side.expected}` : `${side.scheduled} · ${side.status}`
  }
  return { key: r.serviceId || dest, dest, meta, confirmed, atPlatform }
}

function fromPushPort(
  t: PlatformOccupant,
): DisplayOccupant {
  const route = t.origin && t.destination ? `${t.origin} → ${t.destination}` : t.destination || t.origin
  return {
    key: t.headcode || route || 'occ',
    dest: route || t.headcode || 'Train',
    meta: null,
    confirmed: t.platformConfirmed,
    atPlatform: t.atPlatformNow,
  }
}

function OccupantService({ occupant }: { occupant: DisplayOccupant }) {
  return (
    <div className={styles.occupant}>
      <span className={styles.dest}>
        {occupant.atPlatform ? (
          <span className={styles.atPlatformDot} title="At the platform now" aria-hidden="true" />
        ) : (
          occupant.confirmed && (
            <span className={styles.confirmedDot} title="Platform confirmed by Darwin" aria-hidden="true" />
          )
        )}
        {occupant.dest}
      </span>
      {occupant.meta && <span className={styles.meta}>{occupant.meta}</span>}
    </div>
  )
}

/** One platform row: its label plus the next service(s) on it, or "—" when clear. */
function PlatformRow({
  platform,
  occupants,
  index,
}: {
  platform: string
  occupants: DisplayOccupant[]
  index: number
}) {
  return (
    <tr
      className={`${styles.row} ${styles.rowAnimated}`}
      style={{ '--row-index': index } as CSSProperties}
    >
      <td className={`${styles.cell} ${styles.cellPlatform}`}>{platform}</td>
      <td className={styles.cell}>
        {occupants.length === 0 ? (
          <span className={styles['empty-cell']}>—</span>
        ) : (
          <div className={styles.occupants}>
            {occupants.map((o) => (
              <OccupantService key={o.key} occupant={o} />
            ))}
          </div>
        )}
      </td>
    </tr>
  )
}

interface Props {
  crs: string
  /**
   * Live departures + arrivals for this station (the LDB feed, via {@code useStationBoard}). The
   * primary occupant source: it carries each service's platform, origin and destination and works
   * with no Darwin Timetable loaded — unlike the push-port path, which needs the timetable to resolve
   * a train and so goes empty whenever the snapshot expires.
   */
  boardRows: StationBoardRow[]
}

/**
 * Live platform board for a National Rail station. The platform *layout* comes from
 * {@code getStationPlatforms} (the persisted {@code station_platforms} inventory, so every platform
 * shows even when empty). Occupants come from the live departure/arrival board ({@code boardRows}),
 * which needs no timetable; the Darwin push-port feed is layered on as a *confirmed* marker (and as a
 * fallback occupant source for platforms the board doesn't cover). Useful even when the timetable
 * snapshot has expired or the push-port feed is switched off.
 */
export function StationPlatformBoard({ crs, boardRows }: Props) {
  const { data, loading, error } = useStationPlatforms(crs)

  if (loading && !data && boardRows.length === 0) return <PlatformSkeleton />
  if (error && boardRows.length === 0) return <PlatformError message={error} />

  const layout = data?.platforms ?? []

  // Group live board services by their platform (soonest first); skip rows with no platform yet.
  const boardByPlatform = new Map<string, StationBoardRow[]>()
  for (const r of boardRows) {
    const p = r.platform.trim()
    if (!p) continue
    const list = boardByPlatform.get(p)
    if (list) list.push(r)
    else boardByPlatform.set(p, [r])
  }
  for (const list of boardByPlatform.values()) list.sort((a, b) => a.sortSecs - b.sortSecs)

  // Push-port occupants per platform (timetable-dependent; the confirmation source).
  const pushByPlatform = new Map<string, PlatformOccupant[]>(layout.map((p) => [p.platform, p.trains]))

  // Platform order: the persisted layout first (already naturally sorted server-side), then any
  // platform that only the live board knows about.
  const labels = layout.map((p) => p.platform)
  for (const p of boardByPlatform.keys()) if (!labels.includes(p)) labels.push(p)

  const rows = labels.map((label) => {
    const board = boardByPlatform.get(label) ?? []
    const push = pushByPlatform.get(label) ?? []
    const confirmed = push.some((t) => t.platformConfirmed)
    // Darwin movement says a service is standing here now (arr.at ∧ ¬dep.at). The live board can't
    // know this, so it's layered on like the confirmed marker.
    const atPlatform = push.some((t) => t.atPlatformNow)
    // Prefer the board (works without the timetable); fall back to push-port occupants so we never
    // show less than before. Two soonest keep a busy platform readable.
    const occupants =
      board.length > 0
        ? board.slice(0, 2).map((r) => fromBoardRow(r, confirmed, atPlatform))
        : push.map((t) => fromPushPort(t))
    return { label, occupants }
  })

  return (
    <div className={styles.wrapper}>
      <h3 className={styles.heading}>Live platforms</h3>

      {rows.length === 0 ? (
        <div className={styles.empty} role="status">
          <span className={styles.emptyIcon} aria-hidden="true">&#128342;</span>
          <p className={styles.emptyLabel}>No platform data yet</p>
          <p className={styles.emptySub}>Platforms appear as services are scheduled — check back shortly.</p>
        </div>
      ) : (
        <div className={styles.tableWrapper}>
          <table className={styles.table} aria-label="Live platform board">
            <thead>
              <tr>
                <th scope="col" className={`${styles.th} ${styles.thPlatform}`}>Platform</th>
                <th scope="col" className={styles.th}>Service</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <PlatformRow key={r.label} platform={r.label} occupants={r.occupants} index={i} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
