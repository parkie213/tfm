import { useEffect, useState } from 'react'
import type { TrainCallingPoint } from '../proto/tfm/v1/trains_pb'
import type { UseTrainServiceResult } from '../hooks/useTrainService'
import { BoardSkeleton } from './BoardSkeleton'
import styles from './TrainServiceDetail.module.css'
import boardStyles from './BoardPanel.module.css'

// "HH:MM" or "HH:MM:SS" → total seconds since midnight; unparseable → null.
function hhmmSecs(hhmm: string): number | null {
  const m = /^(\d{1,2}):(\d{2})/.exec(hhmm)
  if (!m) return null
  return Number(m[1]) * 3600 + Number(m[2]) * 60
}

// Current local time → seconds since midnight.
function nowSecs(): number {
  const d = new Date()
  return d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds()
}

// The relevant time for progress comparison: departure if present, else arrival.
function stopSecs(stop: TrainCallingPoint): number | null {
  return hhmmSecs(stop.departure || stop.arrival)
}

interface CallingPointRowProps {
  stop: TrainCallingPoint
  onFocusStop: (lat: number, lng: number) => void
  isNext: boolean
  isPast: boolean
}

function CallingPointRow({ stop, onFocusStop, isNext, isPast }: CallingPointRowProps) {
  const hasCoords = stop.lat !== 0 || stop.lng !== 0

  const handleClick = hasCoords ? () => onFocusStop(stop.lat, stop.lng) : undefined
  const handleKeyDown = hasCoords
    ? (e: React.KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onFocusStop(stop.lat, stop.lng) } }
    : undefined

  return (
    <li
      className={[
        styles.stopRow,
        hasCoords ? styles.stopRowClickable : styles.stopRowNoCoords,
        isNext ? styles.stopRowNext : '',
        isPast ? styles.stopRowPast : '',
      ].filter(Boolean).join(' ')}
      onClick={handleClick}
      onKeyDown={handleKeyDown}
      role={hasCoords ? 'button' : undefined}
      tabIndex={hasCoords ? 0 : undefined}
      title={hasCoords ? `Zoom to ${stop.name}` : undefined}
      aria-label={hasCoords ? `Zoom to ${stop.name}` : undefined}
    >
      <span className={styles.timeline} aria-hidden="true" />
      <span className={styles.dot} aria-hidden="true" />
      <span className={styles.arrTime}>{stop.arrival || ''}</span>
      <span className={styles.depTime}>{stop.departure || ''}</span>
      <span className={styles.stopName}>
        {stop.name}
        {isNext && <span className={styles.nextTag}>next stop</span>}
      </span>
    </li>
  )
}

interface Props {
  serviceResult: UseTrainServiceResult
  onFocusStop: (lat: number, lng: number) => void

}

export function TrainServiceDetail({
  serviceResult,
  onFocusStop,
}: Props) {
  const { data, loading, error, unimplemented } = serviceResult

  // Tick every 30s to recompute isPast / isNext based on current time.
  const [tick, setTick] = useState(0)
  useEffect(() => {
    const id = setInterval(() => setTick((n) => n + 1), 30_000)
    return () => clearInterval(id)
  }, [])

  // Compute progress state from wall time.
  const current = nowSecs()
  // Suppress tick from lint warnings — it intentionally drives a recalculation each interval.
  void tick

  const stops = data?.stops ?? []

  // First stop whose time is in the future → that's "next".
  let nextIndex = -1
  for (let i = 0; i < stops.length; i++) {
    const s = stopSecs(stops[i])
    if (s !== null && s >= current) {
      nextIndex = i
      break
    }
  }

  const hasStops = stops.length > 0

  return (
    <div className={styles.wrapper}>
      <div className={styles.header}>
        <div className={styles.titleBlock}>
          <h3 className={styles.title}>
            {data?.headcode && (
              <>
                <span className={styles.headcodeChip}>{data.headcode}</span>
                <span className={styles.arrow}>&rarr;</span>
              </>
            )}
            <span className={styles.routeLabel}>
              {data ? `${data.origin} → ${data.destination}` : 'Train service'}
            </span>
          </h3>
          {data?.toc && <p className={styles.tocLabel}>{data.toc}</p>}
          <p className={styles.subtitle}>Calling points &middot; scheduled times</p>

        </div>
      </div>

      <div className={styles.body}>
        {loading && <BoardSkeleton variant="train" rows={6} header={false} />}

        {!loading && unimplemented && (
          <div className={boardStyles.unimplemented} role="status">
            <span className={boardStyles.unimplementedIcon} aria-hidden="true">&#128338;</span>
            <p className={boardStyles.unimplementedText}>Details unavailable</p>
            <p className={boardStyles.unimplementedSub}>Timetable data isn&rsquo;t enabled yet.</p>
          </div>
        )}

        {!loading && error && (
          <div className={boardStyles.errorWrapper} role="alert">
            <p className={boardStyles.errorTitle}>Could not load calling points</p>
            <p className={boardStyles.errorDetail}>{error}</p>
          </div>
        )}

        {!loading && !error && !unimplemented && data && !data.found && (
          <div className={boardStyles.unimplemented} role="status">
            <span className={boardStyles.unimplementedIcon} aria-hidden="true">&#128644;</span>
            <p className={boardStyles.unimplementedText}>Service not found</p>
            <p className={boardStyles.unimplementedSub}>
              This train couldn&rsquo;t be matched in the timetable.
            </p>
          </div>
        )}

        {!loading && !error && !unimplemented && hasStops && (
          <ol className={styles.stopList} aria-label="Calling points">
            {stops.map((stop, i) => {
              const isPast = nextIndex >= 0 ? i < nextIndex : stopSecs(stop) !== null
              const isNext = i === nextIndex
              return (
                <CallingPointRow
                  key={`${stop.sequence}-${stop.tpl}`}
                  stop={stop}
                  onFocusStop={onFocusStop}
                  isNext={isNext}
                  isPast={isPast}
                />
              )
            })}
          </ol>
        )}
      </div>
    </div>
  )
}
