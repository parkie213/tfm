import type { SelectedRoute } from '../App'
import type { ServiceStop } from '../proto/tfm/v1/routes_pb'
import { useRouteStops } from '../hooks/useDepartures'
import { BoardSkeleton } from './BoardSkeleton'
import styles from './RoutePanel.module.css'
import boardStyles from './BoardPanel.module.css'

function StopRow({
  stop,
  onFocusStop,
}: {
  stop: ServiceStop
  onFocusStop?: (lat: number, lng: number) => void
}) {
  const clickable = !!onFocusStop && (stop.lat !== 0 || stop.lng !== 0)
  return (
    <li
      className={[styles.stopRow, clickable ? styles.stopRowClickable : ''].filter(Boolean).join(' ')}
      onClick={clickable ? () => onFocusStop!(stop.lat, stop.lng) : undefined}
      title={clickable ? 'Zoom to this stop' : undefined}
      aria-label={clickable ? `Zoom to ${stop.name}` : undefined}
    >
      <span className={styles.timeline} aria-hidden="true" />
      <span className={styles.dot} aria-hidden="true" />
      <span className={styles.stopInfo}>
        <span className={styles.stopName}>{stop.name}</span>
        {stop.locality && <span className={styles.locality}>{stop.locality}</span>}
      </span>
    </li>
  )
}

/**
 * Detail card for a route picked by clicking its line on the map. Shows the route number (in its
 * stable map colour), its long name (typically "Origin – Destination"), the operator, and the
 * ordered calling points of the route's fullest variant. The route is highlighted on the map (others
 * dimmed) while this is open; clicking a stop zooms the map to it.
 */
export function RoutePanel({
  route,
  onFocusStop,
}: {
  route: SelectedRoute
  onFocusStop?: (lat: number, lng: number) => void
}) {
  const color = route.color
  const { data, loading, error, unimplemented } = useRouteStops(
    route.shortName || null,
    route.longName,
    route.operator,
  )
  const hasStops = !!data && data.stops.length > 0

  return (
    <div className={styles.wrapper}>
      <div className={styles.header}>
        <span className={styles.badge} style={{ background: color }}>
          {route.shortName || '?'}
        </span>
        <div className={styles.headerText}>
          {route.longName && <h3 className={styles.longName}>{route.longName}</h3>}
          {route.operator && <p className={styles.operator}>{route.operator}</p>}
        </div>
      </div>

      {loading && <BoardSkeleton variant="bus" rows={8} header={false} />}

      {!loading && unimplemented && (
        <div className={boardStyles.unimplemented} role="status">
          <span className={boardStyles.unimplementedIcon} aria-hidden="true">&#128338;</span>
          <p className={boardStyles.unimplementedText}>Stops unavailable</p>
          <p className={boardStyles.unimplementedSub}>Timetable data isn&rsquo;t enabled yet.</p>
        </div>
      )}

      {!loading && error && (
        <div className={boardStyles.errorWrapper} role="alert">
          <p className={boardStyles.errorTitle}>Could not load stops</p>
          <p className={boardStyles.errorDetail}>{error}</p>
        </div>
      )}

      {!loading && !error && !unimplemented && !hasStops && (
        <p className={styles.hint}>No stop list available for this route.</p>
      )}

      {!loading && !error && !unimplemented && hasStops && (
        <ol className={styles.stopList} aria-label={`Stops on route ${route.shortName}`}>
          {data!.stops.map((stop) => (
            <StopRow key={`${stop.sequence}-${stop.atcoCode}`} stop={stop} onFocusStop={onFocusStop} />
          ))}
        </ol>
      )}
    </div>
  )
}
