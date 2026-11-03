import type { CSSProperties } from 'react'
import type { PlatformOccupant } from '../proto/tfm/v1/trains_pb'
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
function fromOccupant(t: PlatformOccupant, index: number): DisplayOccupant {
  const route = t.origin && t.destination ? `${t.origin} → ${t.destination}` : t.destination || t.origin
  const side = t.boardRow?.dep ?? t.boardRow?.arr
  const reTimed = side && /^\d{1,2}:\d{2}$/.test(side.expected) && side.expected !== side.scheduled
  return {
    key: `${t.identity}:${index}`,
    dest: route || t.headcode || 'Train',
    meta: side ? (reTimed ? `${side.scheduled} → ${side.expected}` : `${side.scheduled} · ${side.status}`) : null,
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

/** Renders the reconciled platform occupants supplied by Java. */
export function StationPlatformBoard({ crs }: { crs: string }) {
  const { data, loading, error } = useStationPlatforms(crs)
  if (loading && !data) return <PlatformSkeleton />
  if (error && !data) return <PlatformError message={error} />
  const rows = (data?.platforms ?? []).map(p => ({
    label: p.platform,
    occupants: p.trains.map(fromOccupant),
  }))

  return (
    <div className={styles.wrapper}>
      <h3 className={styles.heading}>Live platforms</h3>
      {data && !data.boardAvailable && (
        <p role="status">Live train board unavailable. Showing available Darwin platform observations.</p>
      )}
      {data && !data.platformDataAvailable && (
        <p role="status">Platform inventory and movement data unavailable. Showing live board services.</p>
      )}

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
