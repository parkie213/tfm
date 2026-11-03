import { useState } from 'react'
import type { CSSProperties } from 'react'
import type { Departure } from '../proto/tfm/v1/boards_pb'
import { StatusBadge } from './StatusBadge'
import { CoachLoading } from './CoachLoading'
import { relativeTime } from '../hooks/useRelativeTime'
import styles from './DepartureBoard.module.css'

interface SelectedService {
  tripId: string
  line: string
  destination: string
  isLive?: boolean
  noGps?: boolean
}

interface Props {
  departure: Departure
  mode: 'departures' | 'arrivals'
  variant?: 'train' | 'bus'
  onSelectService?: (svc: SelectedService) => void
  // Board-level minute clock for "in N min" labels.
  now?: Date
  // Position in the board, drives the staggered reveal.
  index?: number
  // Combined bus board only: the stand/direction this row leaves from, shown in place of the operator.
  stand?: string
}

export function DepartureRow({ departure, mode, variant = 'train', onSelectService, now, index = 0, stand }: Props) {
  const [expanded, setExpanded] = useState(false)
  const isCancelled = departure.status.toLowerCase() === 'cancelled'
  const isDelayed = departure.delayMinutes > 0
  const hasFormation = departure.coaches.length > 0
  const displayName = mode === 'arrivals' ? departure.origin : departure.destination
  const staggerStyle = { '--row-index': index } as CSSProperties

  // Prefer the live expected time when it parses as HH:mm, else the timetable time.
  const rel = !isCancelled && now
    ? relativeTime(departure.expectedTime, now) ?? relativeTime(departure.scheduledTime, now)
    : null
  const relLabel = rel && (
    <span
      className={`${styles.relativeTime} ${
        rel.minutes === 0 ? styles.relativeTimeDue : rel.minutes <= 5 ? styles.relativeTimeImminent : ''
      }`}
    >
      {rel.label}
    </span>
  )

  // Buses: Time | Service | To | Operator (platform/coaches/formation don't apply).
  if (variant === 'bus') {
    const canSelect = !!onSelectService && !!departure.serviceId
    function handleClick() {
      if (canSelect) {
        onSelectService!({
          tripId: departure.serviceId,
          line: departure.line,
          destination: departure.destination,
          isLive: departure.isLive,
          noGps: departure.noGps,
        })
      }
    }
    // Live punctuality (estimated server-side from the vehicle's GPS trajectory).
    const delayMin = departure.delayMinutes
    // Still sitting at its origin before the scheduled start — not early, just not begun.
    const notStarted = departure.isLive && departure.status === 'Not yet departed'
    const punct = departure.isLive
      ? notStarted
        ? { text: 'not yet departed', cls: styles.livePillNotStarted }
        : delayMin >= 2
          ? { text: `~${delayMin} min late`, cls: styles.livePillLate }
          : delayMin <= -2
            ? { text: `~${Math.abs(delayMin)} min early`, cls: styles.livePillEarly }
            : { text: 'on time', cls: styles.livePillOnTime }
      : null
    const showExpected =
      departure.isLive && !notStarted && Math.abs(delayMin) >= 2 &&
      !!departure.expectedTime && departure.expectedTime !== departure.scheduledTime
    return (
      <tr
        className={`${styles.row} ${styles.rowAnimated} ${isCancelled ? styles.rowCancelled : ''} ${canSelect ? styles.rowExpandable : ''}`}
        onClick={canSelect ? handleClick : undefined}
        style={canSelect ? { ...staggerStyle, cursor: 'pointer' } : staggerStyle}
        aria-label={canSelect ? `View calling points for service ${departure.line} to ${departure.destination}` : undefined}
      >
        <td className={styles.cell}>
          <span className={`${styles.time} ${showExpected ? styles.timeSuperseded : ''}`}>
            {departure.scheduledTime}
          </span>
          {showExpected && <span className={styles.liveExpected}>{departure.expectedTime}</span>}
          {relLabel}
          {punct && <span className={`${styles.livePill} ${punct.cls}`}>{punct.text}</span>}
          {/* Scheduled now but no live vehicle located — operator publishes no GPS to BODS. */}
          {departure.noGps && (
            <span className={`${styles.livePill} ${styles.livePillNoGps}`} title="This service is running but has no live GPS position">
              no GPS
            </span>
          )}
        </td>
        <td className={styles.cell}>
          <span className={styles.line}>{departure.line || '—'}</span>
        </td>
        <td className={`${styles.cell} ${styles.destination}`}>{departure.destination}</td>
        <td className={`${styles.cell} ${stand !== undefined ? styles.stand : styles.operator}`}>
          {stand !== undefined
            ? <span className={styles.standPill}>{stand || '—'}</span>
            : departure.operator}
        </td>
      </tr>
    )
  }

  return (
    <>
      <tr
        className={`${styles.row} ${styles.rowAnimated} ${isCancelled ? styles.rowCancelled : ''} ${hasFormation ? styles.rowExpandable : ''}`}
        onClick={hasFormation ? () => setExpanded((v) => !v) : undefined}
        aria-expanded={hasFormation ? expanded : undefined}
        style={staggerStyle}
      >
        <td className={styles.cell}>
          <span className={`${styles.time} ${isDelayed ? styles.timeSuperseded : ''}`}>
            {departure.scheduledTime}
          </span>
          {relLabel}
        </td>
        <td className={styles.cell}>
          <span className={`${styles.expected} ${isDelayed ? styles.late : ''}`}>
            {departure.expectedTime || '—'}
          </span>
        </td>
        <td className={`${styles.cell} ${styles.destination}`}>
          {displayName}
        </td>
        <td className={`${styles.cell} ${styles.platform}`}>
          {departure.platformOrBay || '—'}
        </td>
        <td className={`${styles.cell} ${styles.coaches}`}>
          {departure.coachCount > 0 ? (
            <span className={hasFormation ? styles.coachCountExpandable : undefined}>
              {departure.coachCount}
              {hasFormation && (
                <span className={styles.expandHint} aria-hidden="true">
                  {expanded ? ' ▲' : ' ▼'}
                </span>
              )}
            </span>
          ) : '—'}
        </td>
        <td className={`${styles.cell} ${styles.operator}`}>
          {departure.operator}
        </td>
        <td className={`${styles.cell} ${styles.status}`}>
          <StatusBadge status={departure.status} isLive={departure.isLive} />
        </td>
      </tr>
      {hasFormation && expanded && (
        <tr className={styles.formationRow}>
          <td colSpan={7} className={styles.formationCell}>
            <CoachLoading coaches={departure.coaches} />
          </td>
        </tr>
      )}
    </>
  )
}
