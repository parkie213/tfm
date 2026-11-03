import { useState } from 'react'
import type { CSSProperties } from 'react'
import type { StationBoardRow } from '../hooks/useDepartures'
import { hhmmSecs } from '../hooks/useDepartures'
import type { SelectedTrainService } from '../hooks/useTrainService'
import { StatusBadge } from './StatusBadge'
import styles from './DepartureBoard.module.css'

interface Props {
  rows: StationBoardRow[]
  nrccMessages: string
  title?: string
  crs?: string
  onSelectTrainService?: (svc: SelectedTrainService) => void
}

/**
 * Rail station board. Defaults to two independently-scrollable lists — Departures (destination +
 * departure time) and Arrivals (origin + arrival time) — which is how real station screens work and
 * reads cleanly at a hub like Nottingham. A "Combined" checkbox switches to the single merged list
 * (one row per service with both Arr and Dep), which is compact for a small through-station like
 * Melton where every train both arrives and departs. The choice is the user's; the layout never
 * switches on its own (an auto-rule would make the board flap as traffic changes).
 */
export function StationBoard({ rows, nrccMessages, title, crs, onSelectTrainService }: Props) {
  const [combined, setCombined] = useState(false)

  const departures = rows
    .filter((r) => r.dep)
    .sort((a, b) => hhmmSecs(a.dep!.scheduled) - hhmmSecs(b.dep!.scheduled))
  const arrivals = rows
    .filter((r) => r.arr)
    .sort((a, b) => hhmmSecs(a.arr!.scheduled) - hhmmSecs(b.arr!.scheduled))
  const hasScheduledRows = rows.length > 0 && rows.some((r) => !r.isLive)

  return (
    <div className={styles.boardWrapper}>
      {nrccMessages && (
        <div className={styles.disruptionBanner} role="alert">
          <span className={styles.disruptionIcon}>&#9888;</span>
          <span>{nrccMessages}</span>
        </div>
      )}

      <div className={styles.boardHeader}>
        <div>
          <h2 className={styles.stationName}>{title ?? 'Departures & arrivals'}</h2>
          {hasScheduledRows && (
            <p className={styles.boardSubtitle}>
              Dimmed rows are scheduled times — live updates appear as trains approach.
            </p>
          )}
        </div>
        {rows.length > 0 && (
          <label className={styles.viewToggle}>
            <input
              type="checkbox"
              checked={combined}
              onChange={(e) => setCombined(e.target.checked)}
            />
            Combined
          </label>
        )}
      </div>

      {rows.length === 0 ? (
        <div className={styles.emptyState}>
          <span className={styles.emptyIcon} aria-hidden="true">&#x1F967;</span>
          <p className={styles.emptyLabel}>No services currently available.</p>
          <p className={styles.emptySub}>All quiet for now — check back shortly.</p>
        </div>
      ) : combined ? (
        <CombinedTable rows={rows} crs={crs} onSelectTrainService={onSelectTrainService} />
      ) : (
        <>
          <BoardSection
            heading="Departures"
            side="dep"
            rows={departures}
            crs={crs}
            onSelectTrainService={onSelectTrainService}
          />
          <BoardSection
            heading="Arrivals"
            side="arr"
            rows={arrivals}
            crs={crs}
            onSelectTrainService={onSelectTrainService}
          />
        </>
      )}
    </div>
  )
}

// ---- split view: one section per direction ----

function BoardSection({
  heading,
  side,
  rows,
  crs,
  onSelectTrainService,
}: {
  heading: string
  side: 'dep' | 'arr'
  rows: StationBoardRow[]
  crs?: string
  onSelectTrainService?: (svc: SelectedTrainService) => void
}) {
  const isDep = side === 'dep'
  return (
    <section className={styles.boardSection}>
      <h3 className={styles.sectionTitle}>{heading}</h3>
      {rows.length === 0 ? (
        <p className={styles.sectionEmpty}>No {isDep ? 'departures' : 'arrivals'} listed.</p>
      ) : (
        <div className={`${styles.tableWrapper} ${styles.scrollList}`}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th scope="col" className={`${styles.th} ${styles.destination}`}>
                  {isDep ? 'Destination' : 'Origin'}
                </th>
                <th scope="col" className={styles.th}>{isDep ? 'Dep' : 'Arr'}</th>
                <th scope="col" className={`${styles.th} ${styles.platform}`}>Plat</th>
                <th scope="col" className={`${styles.th} ${styles.status}`}>Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <DirectionRow
                  key={r.serviceId || `${side}-${r.sortSecs}-${i}`}
                  row={r}
                  side={side}
                  index={i}
                  crs={crs}
                  onSelectTrainService={onSelectTrainService}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function DirectionRow({
  row,
  side,
  index,
  crs,
  onSelectTrainService,
}: {
  row: StationBoardRow
  side: 'dep' | 'arr'
  index: number
  crs?: string
  onSelectTrainService?: (svc: SelectedTrainService) => void
}) {
  const isDep = side === 'dep'
  const timeSide = isDep ? row.dep : row.arr
  // Departures show where the train is going; arrivals where it came from.
  const place = isDep ? row.destination || '(ends here)' : row.origin || '(starts here)'
  const status = timeSide?.status ?? ''
  const isCancelled = status.toLowerCase() === 'cancelled'
  const isScheduled = !row.isLive

  // Clickable when we have a CRS and a scheduled time to disambiguate the service. For arrivals the
  // backend matches on arrival time and uses the origin as the destination disambiguator.
  const canSelect = !!crs && !!onSelectTrainService && !!timeSide?.scheduled
  function handleSelect() {
    if (!canSelect || !crs || !onSelectTrainService || !timeSide) return
    onSelectTrainService({
      crs,
      scheduledTime: timeSide.scheduled,
      destination: isDep ? row.destination || '' : row.origin || '',
      isArrival: !isDep,
    })
  }

  return (
    <tr
      className={`${styles.row} ${styles.rowAnimated} ${isCancelled ? styles.rowCancelled : ''} ${isScheduled ? styles.rowScheduled : ''} ${canSelect ? styles.rowExpandable : ''}`}
      style={{ '--row-index': index } as CSSProperties}
      onClick={canSelect ? handleSelect : undefined}
      onKeyDown={canSelect ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleSelect() } } : undefined}
      role={canSelect ? 'button' : undefined}
      tabIndex={canSelect ? 0 : undefined}
      aria-label={canSelect ? `View calling points ${isDep ? 'to' : 'from'} ${place}` : undefined}
    >
      <td className={`${styles.cell} ${styles.destination}`}>{place}</td>
      <td className={styles.cell}><TimeCell side={timeSide} /></td>
      <td className={`${styles.cell} ${styles.platform}`}>{row.platform || '—'}</td>
      <td className={`${styles.cell} ${styles.status}`}>
        <StatusBadge status={status} isLive={row.isLive} />
      </td>
    </tr>
  )
}

// ---- combined view: one row per service, Arr + Dep side by side ----

function CombinedTable({
  rows,
  crs,
  onSelectTrainService,
}: {
  rows: StationBoardRow[]
  crs?: string
  onSelectTrainService?: (svc: SelectedTrainService) => void
}) {
  return (
    <div className={`${styles.tableWrapper} ${styles.scrollList}`}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th scope="col" className={`${styles.th} ${styles.destination}`}>From &rarr; To</th>
            <th scope="col" className={styles.th}>Arr</th>
            <th scope="col" className={styles.th}>Dep</th>
            <th scope="col" className={`${styles.th} ${styles.platform}`}>Plat</th>
            <th scope="col" className={`${styles.th} ${styles.status}`}>Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <CombinedRow
              key={r.serviceId || `${r.sortSecs}-${i}`}
              row={r}
              index={i}
              crs={crs}
              onSelectTrainService={onSelectTrainService}
            />
          ))}
        </tbody>
      </table>
    </div>
  )
}

function CombinedRow({
  row,
  index,
  crs,
  onSelectTrainService,
}: {
  row: StationBoardRow
  index: number
  crs?: string
  onSelectTrainService?: (svc: SelectedTrainService) => void
}) {
  const isCancelled = (row.dep?.status ?? row.arr?.status ?? '').toLowerCase() === 'cancelled'
  const isScheduled = !row.isLive
  const status = row.dep?.status ?? row.arr?.status ?? ''
  const from = row.origin || '(starts here)'
  const to = row.destination || '(ends here)'

  // Prefer the departure side; fall back to arrival (terminating trains).
  const canSelect = !!crs && !!onSelectTrainService && (!!row.dep?.scheduled || !!row.arr?.scheduled)
  function handleSelect() {
    if (!canSelect || !crs || !onSelectTrainService) return
    if (row.dep?.scheduled) {
      onSelectTrainService({ crs, scheduledTime: row.dep.scheduled, destination: row.destination || '', isArrival: false })
    } else if (row.arr?.scheduled) {
      onSelectTrainService({ crs, scheduledTime: row.arr.scheduled, destination: row.origin || '', isArrival: true })
    }
  }

  return (
    <tr
      className={`${styles.row} ${styles.rowAnimated} ${isCancelled ? styles.rowCancelled : ''} ${isScheduled ? styles.rowScheduled : ''} ${canSelect ? styles.rowExpandable : ''}`}
      style={{ '--row-index': index } as CSSProperties}
      onClick={canSelect ? handleSelect : undefined}
      onKeyDown={canSelect ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleSelect() } } : undefined}
      role={canSelect ? 'button' : undefined}
      tabIndex={canSelect ? 0 : undefined}
      aria-label={canSelect ? `View calling points for ${from} to ${to}` : undefined}
    >
      <td className={`${styles.cell} ${styles.destination}`}>{from} &rarr; {to}</td>
      <td className={styles.cell}><TimeCell side={row.arr} /></td>
      <td className={styles.cell}><TimeCell side={row.dep} /></td>
      <td className={`${styles.cell} ${styles.platform}`}>{row.platform || '—'}</td>
      <td className={`${styles.cell} ${styles.status}`}>
        <StatusBadge status={status} isLive={row.isLive} />
      </td>
    </tr>
  )
}

/** How far behind schedule the live expected time runs, as a compact chip ("+14m", "+1h08") plus a full
 *  label for the tooltip. null when on time, early, or either time doesn't parse. */
function delayLabel(scheduled: string, expected: string): { short: string; full: string } | null {
  const s = hhmmSecs(scheduled)
  const e = hhmmSecs(expected)
  if (s === Number.MAX_SAFE_INTEGER || e === Number.MAX_SAFE_INTEGER) return null
  let mins = Math.round((e - s) / 60)
  if (mins < -720) mins += 1440 // expected crossed midnight past a late-evening schedule
  if (mins <= 0) return null // on time or (rare) early — the times alone tell that story
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return {
    short: h ? `+${h}h${String(m).padStart(2, '0')}` : `+${mins}m`,
    full: h ? `${h}h ${m}m late` : `${mins} min late`,
  }
}

/** One arrival/departure time: scheduled, struck through when a live expected time supersedes it, with
 *  an explicit lateness chip so "how late" doesn't need mental arithmetic. */
function TimeCell({ side }: { side: StationBoardRow['arr'] }) {
  if (!side) return <span className={styles.expected}>&mdash;</span>
  const superseded =
    !!side.expected && /^\d{1,2}:\d{2}$/.test(side.expected) && side.expected !== side.scheduled
  const delay = superseded ? delayLabel(side.scheduled, side.expected) : null
  return (
    <>
      <span className={`${styles.time} ${superseded ? styles.timeSuperseded : ''}`}>{side.scheduled}</span>
      {superseded && <span className={styles.liveExpected}>{side.expected}</span>}
      {delay && <span className={styles.delayChip} title={delay.full}>{delay.short}</span>}
    </>
  )
}
