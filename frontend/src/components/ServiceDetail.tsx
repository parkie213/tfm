import type { ServiceStop } from '../proto/tfm/v1/routes_pb'
import type { UseServiceStopsResult } from '../hooks/useDepartures'
import { BoardSkeleton } from './BoardSkeleton'
import styles from './ServiceDetail.module.css'
import boardStyles from './BoardPanel.module.css'

interface Props {
  service: { line: string; destination: string }
  stopsResult: UseServiceStopsResult
  onFocusStop: (lat: number, lng: number, stopKey?: string) => void
  // Whether the panel is tracking a live bus.
  isLiveVehicle?: boolean
  // A live vehicle operating this (scheduled) service, if one exists and we're not already tracking it.
  liveVehicleId?: string | null
  // Still searching the whole feed for this service's bus; or searched and found none though it's flagged live.
  liveLocating?: boolean
  liveUnavailable?: boolean
  onLocateVehicle?: (id: string) => void
  // Re-centre the camera on the bus we're already tracking (follow is never forced).
  onRecenterVehicle?: () => void
  // While the camera is actively locked on the bus, the button becomes "Stop following" instead — so it
  // isn't a no-op right after selecting/recentring. (GL map only; falls back to "Recentre" elsewhere.)
  isFollowing?: boolean
  onCancelFollow?: () => void
}

function StopRow({
  stop,
  onFocusStop,
}: {
  stop: ServiceStop
  onFocusStop: (lat: number, lng: number, stopKey?: string) => void
}) {
  const hasCoords = stop.lat !== 0 || stop.lng !== 0
  // Same key the RouteOverlay uses for its calling-point markers, so a clicked row highlights the
  // matching dot on the map (sequence + atco: a looping route revisits a stop).
  const stopKey = `${stop.sequence}-${stop.atcoCode}`
  return (
    <li
      className={[
        styles.stopRow,
        hasCoords ? styles.stopRowClickable : '',
      ].filter(Boolean).join(' ')}
      onClick={hasCoords ? () => onFocusStop(stop.lat, stop.lng, stopKey) : undefined}
      title={hasCoords ? 'Zoom to this stop' : undefined}
      aria-label={hasCoords ? `Zoom to ${stop.name}` : undefined}
    >
      <span className={styles.timeline} aria-hidden="true" />
      <span className={styles.dot} aria-hidden="true" />
      <span className={styles.time}>{stop.scheduledTime}</span>
      <span className={styles.stopInfo}>
        <span className={styles.stopName}>
          {stop.name}
        </span>
        {stop.locality && <span className={styles.locality}>{stop.locality}</span>}
      </span>
    </li>
  )
}

export function ServiceDetail({ service, stopsResult, onFocusStop, isLiveVehicle = false, liveVehicleId = null, liveLocating = false, liveUnavailable = false, onLocateVehicle, onRecenterVehicle, isFollowing = false, onCancelFollow }: Props) {
  const { data, loading, error, unimplemented } = stopsResult
  const hasStops = !!data && data.stops.length > 0

  return (
    <div className={styles.wrapper}>
      <div className={styles.header}>
        {/* No back button here: the panel header owns Close/Stop (see BoardPanel), matching
            RoutePanel. A second back control was redundant — and mislabelled when tracking a bus. */}
        <div className={styles.titleBlock}>
          <h3 className={styles.title}>
            <span className={styles.lineBadge}>{service.line}</span>
            <span className={styles.arrow}>&rarr;</span>
            <span className={styles.destination}>{service.destination}</span>
          </h3>
          <p className={styles.subtitle}>
            {isLiveVehicle ? "Following this bus live · scheduled calling times" : "Calling points · scheduled times"}
          </p>
          {/* "Where is it now": locate a scheduled service's live bus, or re-centre on the tracked one.
              Follow is never forced, so this is how the user snaps back after panning to a stop. */}
          {isLiveVehicle && isFollowing && onCancelFollow ? (
            // Camera already locked on the bus → offer to stop, not a redundant recentre.
            <button className={styles.locateBtn} onClick={onCancelFollow}>
              &#x2715; Stop following
            </button>
          ) : isLiveVehicle && onRecenterVehicle ? (
            <button className={styles.locateBtn} onClick={onRecenterVehicle}>
              &#x1F4CD; Recentre on bus
            </button>
          ) : !isLiveVehicle && liveVehicleId && onLocateVehicle ? (
            <button className={styles.locateBtn} onClick={() => onLocateVehicle(liveVehicleId)}>
              &#x1F4CD; Locate bus on map
            </button>
          ) : !isLiveVehicle && liveLocating ? (
            <p className={styles.locateNote}>Locating bus&hellip;</p>
          ) : !isLiveVehicle && liveUnavailable ? (
            <p className={styles.locateNote}>
              No live position is currently available for this service.
            </p>
          ) : !isLiveVehicle ? (
            // A scheduled service that isn't running yet has no live vehicle to locate. Say so
            // explicitly rather than leaving the slot blank — otherwise the user can't tell whether
            // live tracking is unsupported, broken, or simply hasn't started ("don't know if that
            // service even has GPS tracking").
            <p className={styles.locateNote}>
              Live position appears here once this bus is running.
            </p>
          ) : null}
        </div>
      </div>

      <div className={styles.body}>
        {loading && <BoardSkeleton variant="bus" rows={8} header={false} />}

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

        {/* Live bus we couldn't tie to a timetabled trip (e.g. the feed omitted its journey id). */}
        {!loading && !error && !unimplemented && !hasStops && isLiveVehicle && (
          <div className={boardStyles.unimplemented} role="status">
            <span className={boardStyles.unimplementedIcon} aria-hidden="true">&#128663;</span>
            <p className={boardStyles.unimplementedText}>Live route unavailable</p>
            <p className={boardStyles.unimplementedSub}>
              We&rsquo;re still following this bus on the map, but couldn&rsquo;t match it to a timetabled route.
            </p>
          </div>
        )}

        {!loading && !error && !unimplemented && hasStops && (
          <ol className={styles.stopList} aria-label="Calling points">
            {data!.stops.map((stop) => (
              <StopRow key={`${stop.sequence}-${stop.atcoCode}`} stop={stop} onFocusStop={onFocusStop} />
            ))}
          </ol>
        )}
      </div>
    </div>
  )
}
