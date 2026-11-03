import type { Departure } from '../proto/tfm/v1/boards_pb'
import { DepartureRow } from './DepartureRow'
import { useRelativeTime } from '../hooks/useRelativeTime'
import styles from './DepartureBoard.module.css'

interface SelectedService {
  tripId: string
  line: string
  destination: string
}

interface Props {
  rows: Departure[]
  stationName: string
  nrccMessages: string
  mode: 'departures' | 'arrivals'
  // 'train' shows platform/coaches/status; 'bus' shows the service/line number instead.
  variant?: 'train' | 'bus'
  // Heading shown above the board; defaults to the station name.
  title?: string
  // Override the empty-state message.
  emptyLabel?: string
  // Small subtitle rendered below the title (e.g. "Scheduled times").
  subtitle?: string
  // Bus only: called when the user clicks a row to view its calling points.
  onSelectService?: (svc: SelectedService) => void
  // Combined bus board only: a stand/direction label per row (index-aligned with `rows`). When given,
  // the bus board shows a "Stand" column in place of "Operator" so each row says where it leaves from.
  standLabels?: string[]
}

function EmptyState({ label }: { label: string }) {
  return (
    <div className={styles.emptyState}>
      <span className={styles.emptyIcon} aria-hidden="true">&#x1F967;</span>
      <p className={styles.emptyLabel}>{label}</p>
      <p className={styles.emptySub}>All quiet for now — check back shortly.</p>
    </div>
  )
}

export function DepartureBoard({ rows, stationName, nrccMessages, mode, variant = 'train', title, emptyLabel, subtitle, onSelectService, standLabels }: Props) {
  // One minute-aligned clock for the whole board; rows derive "in N min" labels from it.
  const now = useRelativeTime()
  const defaultEmptyLabel = mode === 'arrivals' ? 'No arrivals currently available.' : 'No departures currently available.'
  const resolvedEmptyLabel = emptyLabel ?? defaultEmptyLabel
  const destinationHeader = mode === 'arrivals' ? 'From' : 'To'
  const isBus = variant === 'bus'

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
          <h2 className={styles.stationName}>{title ?? stationName}</h2>
          {subtitle && <p className={styles.boardSubtitle}>{subtitle}</p>}
        </div>
      </div>

      {rows.length === 0 ? (
        <EmptyState label={resolvedEmptyLabel} />
      ) : (
        <div className={styles.tableWrapper}>
          <table className={styles.table}>
            <thead>
              {isBus ? (
                <tr>
                  <th scope="col" className={styles.th}>Time</th>
                  <th scope="col" className={styles.th}>Service</th>
                  <th scope="col" className={`${styles.th} ${styles.destination}`}>{destinationHeader}</th>
                  <th scope="col" className={`${styles.th} ${standLabels ? styles.stand : styles.operator}`}>
                    {standLabels ? 'Stand' : 'Operator'}
                  </th>
                </tr>
              ) : (
                <tr>
                  <th scope="col" className={styles.th}>Time</th>
                  <th scope="col" className={styles.th}>Expected</th>
                  <th scope="col" className={`${styles.th} ${styles.destination}`}>{destinationHeader}</th>
                  <th scope="col" className={`${styles.th} ${styles.platform}`}>Platform</th>
                  <th scope="col" className={`${styles.th} ${styles.coaches}`}>Coaches</th>
                  <th scope="col" className={`${styles.th} ${styles.operator}`}>Operator</th>
                  <th scope="col" className={`${styles.th} ${styles.status}`}>Status</th>
                </tr>
              )}
            </thead>
            <tbody>
              {rows.map((dep, i) => (
                <DepartureRow
                  key={dep.serviceId || `${dep.scheduledTime}-${mode === 'arrivals' ? dep.origin : dep.destination}`}
                  departure={dep}
                  mode={mode}
                  variant={variant}
                  onSelectService={onSelectService}
                  now={now}
                  index={i}
                  stand={standLabels?.[i]}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
