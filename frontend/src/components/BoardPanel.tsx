import { useState, useEffect, useRef, useMemo } from 'react'
import { useStationBoard, useBusDepartures } from '../hooks/useDepartures'
import { useCombinedBusDepartures } from '../hooks/useTownCentreBuses'
import type { UseServiceStopsResult } from '../hooks/useDepartures'
import { DepartureBoard } from './DepartureBoard'
import { StationBoard } from './StationBoard'
import { StationPlatformBoard } from './StationPlatformBoard'
import { BoardSkeleton } from './BoardSkeleton'
import { ServiceDetail } from './ServiceDetail'
import { TrainServiceDetail } from './TrainServiceDetail'
import { RoutePanel } from './RoutePanel'
import { MELTON_STATION } from '../constants/stops'
import styles from './BoardPanel.module.css'
import type { SelectedStop, SelectedService, SelectedRoute } from '../App'
import type { SelectedTrainService } from '../hooks/useTrainService'
import type { UseTrainServiceResult } from '../hooks/useTrainService'

const MAX_DAY_OFFSET = 13 // ~2 weeks ahead (GTFS calendar permitting)

/** Local "yyyy-MM-dd" for today + offset days; '' for today (upcoming mode). */
function dateForOffset(offset: number): string {
  if (offset === 0) return ''
  const d = new Date()
  d.setDate(d.getDate() + offset)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** Local "yyyy-MM-dd" for a Date (whole-day query key). */
function isoDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function dayLabel(offset: number): string {
  if (offset === 0) return 'Today'
  if (offset === 1) return 'Tomorrow'
  const d = new Date()
  d.setDate(d.getDate() + offset)
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })
}

function ErrorState({ message }: { message: string }) {
  return (
    <div className={styles.errorWrapper} role="alert">
      <p className={styles.errorTitle}>Could not load board</p>
      <p className={styles.errorDetail}>{message}</p>
      <p className={styles.errorHint}>Retrying automatically.</p>
    </div>
  )
}

function StationBoardView({
  crs,
  onSelectTrainService,
}: {
  crs?: string
  onSelectTrainService?: (svc: SelectedTrainService) => void
}) {
  const { rows, nrccMessages, loading, error } = useStationBoard(crs || undefined)
  if (loading) return <BoardSkeleton variant="train" />
  if (error) return <ErrorState message={error} />
  return (
    <>
      <StationBoard
        rows={rows}
        nrccMessages={nrccMessages}
        crs={crs}
        onSelectTrainService={onSelectTrainService}
      />
      {crs && <StationPlatformBoard crs={crs} boardRows={rows} />}
    </>
  )
}

/** NaPTAN rail-family stop types (National Rail + heritage/metro/tram). */
function isRailStopType(stopType?: string): boolean {
  return stopType === 'RLY' || stopType === 'RSE' || stopType === 'MET'
}

/** Shown for a rail-family stop with no National Rail board (metro/tram, heritage, closed lines). */
function NoLiveBoard() {
  return (
    <div className={styles.unimplemented} role="status">
      <span className={styles.unimplementedIcon} aria-hidden="true">&#128642;</span>
      <p className={styles.unimplementedText}>No live board for this station</p>
      <p className={styles.unimplementedSub}>
        Live train times are only available for National Rail stations.
      </p>
    </div>
  )
}

/** Routes a selected stop to the right board: live trains for a National Rail station (has a CRS),
 * an explicit no-board notice for other rail/metro stops, or the bus departures board otherwise. */
function StopBoardView({
  stop,
  onSelectService,
  onSelectTrainService,
}: {
  stop: SelectedStop
  onSelectService: (svc: SelectedService) => void
  onSelectTrainService?: (svc: SelectedTrainService) => void
}) {
  if (isRailStopType(stop.stopType)) {
    return stop.crs
      ? <StationBoardView crs={stop.crs} onSelectTrainService={onSelectTrainService} />
      : <NoLiveBoard />
  }
  // A group of co-located stands → one combined board across them all.
  if (stop.group && stop.group.length > 1) {
    return <CombinedBusBoard group={stop.group} onSelectService={onSelectService} />
  }
  return <BusDeparturesBoard atcoCode={stop.atcoCode} onSelectService={onSelectService} />
}

interface CombinedBusBoardProps {
  group: { atcoCode: string; indicator: string }[]
  onSelectService: (svc: SelectedService) => void
}

/** One board merging every stand of a grouped stop (e.g. a bus station's S1–S4), time-sorted, each row
 *  tagged with the stand it leaves from. Reuses the TV view's combined-departures hook (parallel
 *  per-stop fetch + dedupe-by-trip + sort). Mirrors the single-stop board's day-stepper / "show earlier"
 *  / auto-advance-to-tomorrow so a grouped stop has the same timetable controls. */
function CombinedBusBoard({ group, onSelectService }: CombinedBusBoardProps) {
  const [dayOffset, setDayOffset] = useState(0)
  const [jumped, setJumped] = useState(false)
  const [showEarlier, setShowEarlier] = useState(false)
  const autoAdvancedFor = useRef<string | null>(null)

  const atcoCodes = useMemo(() => group.map((g) => g.atcoCode), [group])
  const indicatorByAtco = useMemo(() => new Map(group.map((g) => [g.atcoCode, g.indicator])), [group])
  const groupKey = useMemo(() => [...atcoCodes].sort().join(','), [atcoCodes])

  const wholeDayToday = dayOffset === 0 && showEarlier
  const date = wholeDayToday ? isoDate(new Date()) : dateForOffset(dayOffset)
  const { departures, loading, error, unimplemented, lastUpdated, truncated } =
    useCombinedBusDepartures(atcoCodes, date)

  // New group selected → back to today.
  useEffect(() => {
    setDayOffset(0)
    setJumped(false)
    setShowEarlier(false)
    autoAdvancedFor.current = null
  }, [groupKey])

  // No more buses today → jump to tomorrow once (don't loop on groups with no service).
  useEffect(() => {
    if (
      dayOffset === 0 && !loading && !error && !unimplemented &&
      departures.length === 0 && autoAdvancedFor.current !== groupKey
    ) {
      autoAdvancedFor.current = groupKey
      setDayOffset(1)
      setJumped(true)
    }
  }, [departures, loading, error, unimplemented, dayOffset, groupKey])

  function stepTo(next: number) {
    setJumped(false)
    setShowEarlier(false)
    setDayOffset(Math.max(0, Math.min(MAX_DAY_OFFSET, next)))
  }

  const stepper = (
    <div className={styles.dayStepper}>
      <button className={styles.dayBtn} onClick={() => stepTo(dayOffset - 1)} disabled={dayOffset === 0} aria-label="Previous day">&lsaquo;</button>
      <span className={styles.dayLabel}>{dayLabel(dayOffset)}</span>
      <button className={styles.dayBtn} onClick={() => stepTo(dayOffset + 1)} disabled={dayOffset >= MAX_DAY_OFFSET} aria-label="Next day">&rsaquo;</button>
    </div>
  )

  let body
  if (loading && departures.length === 0) {
    body = <BoardSkeleton variant="bus" />
  } else if (unimplemented) {
    body = (
      <div className={styles.unimplemented} role="status">
        <span className={styles.unimplementedIcon} aria-hidden="true">&#128652;</span>
        <p className={styles.unimplementedText}>Bus times aren&rsquo;t available yet</p>
        <p className={styles.unimplementedSub}>We&rsquo;re working on it &mdash; check back soon.</p>
      </div>
    )
  } else if (error) {
    body = <ErrorState message={error} />
  } else {
    let title: string
    if (wholeDayToday) title = "Today's buses"
    else if (dayOffset === 0) title = 'Next buses'
    else title = `Timetable — ${dayLabel(dayOffset)}`

    // Only show the per-row Stand column when the visible services actually leave from DIFFERENT stands.
    // Often (e.g. a Sunday when only the town circulars run) every bus uses one stand, and a column of
    // identical "Stand S2" reads as broken — so we drop the column and state the single stand once.
    const labels = departures.map((d) => indicatorByAtco.get(d.atcoCode) || '—')
    const distinctStands = [...new Set(labels)]
    const showStandColumn = distinctStands.length > 1

    // Freshness only matters in the live "upcoming" view (it polls, unlike the single board's stream);
    // a stepped/whole-day timetable is static, so we don't show "updated …" there.
    const upcoming = dayOffset === 0 && !wholeDayToday
    const updated = lastUpdated
      ? `updated ${lastUpdated.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
      : 'updating…'
    const place =
      distinctStands.length === 1 ? `from ${distinctStands[0]}` : `${group.length} stands`
    body = (
      <DepartureBoard
        title={title}
        rows={departures.map((d) => d.departure)}
        standLabels={showStandColumn ? labels : undefined}
        stationName=""
        nrccMessages=""
        mode="departures"
        variant="bus"
        emptyLabel={dayOffset === 0 ? 'No more buses today.' : 'No buses scheduled on this day.'}
        subtitle={upcoming ? `${place} · ${updated}` : place}
        onSelectService={onSelectService}
      />
    )
  }

  const canToggleEarlier = dayOffset === 0 && !loading && !error && !unimplemented

  return (
    <>
      {stepper}
      {canToggleEarlier && (
        <button className={styles.earlierBtn} onClick={() => setShowEarlier((v) => !v)} aria-expanded={showEarlier}>
          {showEarlier ? '↓ Upcoming only' : '↑ Show earlier buses'}
        </button>
      )}
      {jumped && <p className={styles.jumpNote}>No more buses today &mdash; showing tomorrow.</p>}
      {truncated && dayOffset === 0 && !wholeDayToday && (
        <p className={styles.jumpNote}>Showing the next {departures.length} &mdash; step a day or show earlier for the full timetable.</p>
      )}
      {body}
    </>
  )
}

interface BusBoardProps {
  atcoCode: string
  onSelectService: (svc: SelectedService) => void
}

function BusDeparturesBoard({ atcoCode, onSelectService }: BusBoardProps) {
  const [dayOffset, setDayOffset] = useState(0)
  const [jumped, setJumped] = useState(false)
  // Today only: switch from "upcoming from now" to the full day so earlier buses show.
  const [showEarlier, setShowEarlier] = useState(false)
  const autoAdvancedFor = useRef<string | null>(null)
  const wholeDayToday = dayOffset === 0 && showEarlier
  const date = wholeDayToday ? isoDate(new Date()) : dateForOffset(dayOffset)
  const { data, error, unimplemented, loading } = useBusDepartures(atcoCode, date)

  // New stop selected → start at today again.
  useEffect(() => {
    setDayOffset(0)
    setJumped(false)
    setShowEarlier(false)
    autoAdvancedFor.current = null
  }, [atcoCode])

  // No more buses today → jump to tomorrow once (don't loop on stops with no service).
  useEffect(() => {
    if (
      dayOffset === 0 && !loading && !error && !unimplemented &&
      data && data.departures.length === 0 &&
      autoAdvancedFor.current !== atcoCode
    ) {
      autoAdvancedFor.current = atcoCode
      setDayOffset(1)
      setJumped(true)
    }
  }, [data, loading, error, unimplemented, dayOffset, atcoCode])

  function stepTo(next: number) {
    setJumped(false)
    setShowEarlier(false)
    setDayOffset(Math.max(0, Math.min(MAX_DAY_OFFSET, next)))
  }

  const stepper = (
    <div className={styles.dayStepper}>
      <button
        className={styles.dayBtn}
        onClick={() => stepTo(dayOffset - 1)}
        disabled={dayOffset === 0}
        aria-label="Previous day"
      >
        &lsaquo;
      </button>
      <span className={styles.dayLabel}>{dayLabel(dayOffset)}</span>
      <button
        className={styles.dayBtn}
        onClick={() => stepTo(dayOffset + 1)}
        disabled={dayOffset >= MAX_DAY_OFFSET}
        aria-label="Next day"
      >
        &rsaquo;
      </button>
    </div>
  )

  let body
  if (loading) {
    body = <BoardSkeleton variant="bus" />
  } else if (unimplemented) {
    body = (
      <div className={styles.unimplemented} role="status">
        <span className={styles.unimplementedIcon} aria-hidden="true">&#128652;</span>
        <p className={styles.unimplementedText}>Bus times aren&rsquo;t available yet</p>
        <p className={styles.unimplementedSub}>We&rsquo;re working on it &mdash; check back soon.</p>
      </div>
    )
  } else if (error) {
    body = <ErrorState message={error} />
  } else {
    let title: string
    if (wholeDayToday) title = "Today's buses"
    else if (dayOffset === 0) title = 'Next buses'
    else title = `Timetable — ${dayLabel(dayOffset)}`
    body = (
      <DepartureBoard
        title={title}
        rows={data?.departures ?? []}
        stationName={data?.stopName ?? ''}
        nrccMessages=""
        mode="departures"
        variant="bus"
        emptyLabel={dayOffset === 0 ? 'No more buses today.' : 'No buses scheduled on this day.'}
        subtitle="Tap a row to see calling points"
        onSelectService={onSelectService}
      />
    )
  }

  // Today only: let the user reveal buses that have already departed.
  const canToggleEarlier = dayOffset === 0 && !loading && !error && !unimplemented

  return (
    <>
      {stepper}
      {canToggleEarlier && (
        <button
          className={styles.earlierBtn}
          onClick={() => setShowEarlier((v) => !v)}
          aria-expanded={showEarlier}
        >
          {showEarlier ? '↓ Upcoming only' : '↑ Show earlier buses'}
        </button>
      )}
      {jumped && (
        <p className={styles.jumpNote}>No more buses today &mdash; showing tomorrow.</p>
      )}
      {body}
    </>
  )
}

interface Props {
  collapsed: boolean
  onToggleCollapse: () => void
  selectedStop: SelectedStop | null
  onClearStop: () => void
  selectedService: SelectedService | null
  onSelectService: (svc: SelectedService) => void
  onClearService: () => void
  selectedRoute: SelectedRoute | null
  onClearRoute: () => void
  serviceStops: UseServiceStopsResult
  onFocusStop: (lat: number, lng: number) => void
  // Set when the selected service is a live bus being tracked (vs a scheduled board row).
  isLiveVehicle: boolean
  // The live vehicle currently operating a board-selected service (null if none / already tracking).
  liveVehicleId: string | null
  // Looking up the whole feed for this service's bus / looked and found none despite it being flagged live.
  liveLocating: boolean
  liveUnavailable: boolean
  onLocateVehicle: (id: string) => void
  onRecenterVehicle: () => void
  // Whether the camera is currently following this bus, and how to stop — toggles the recentre button.
  isFollowingVehicle?: boolean
  onCancelFollowVehicle?: () => void
  // Train service detail (click a station-board row).
  selectedTrainService: SelectedTrainService | null
  onSelectTrainService: (svc: SelectedTrainService) => void
  onClearTrainService: () => void
  trainServiceResult: UseTrainServiceResult

}

export function BoardPanel({
  collapsed,
  onToggleCollapse,
  selectedStop,
  onClearStop,
  selectedService,
  onSelectService,
  onClearService,
  selectedRoute,
  onClearRoute,
  serviceStops,
  onFocusStop,
  isLiveVehicle,
  liveVehicleId,
  liveLocating,
  liveUnavailable,
  onLocateVehicle,
  onRecenterVehicle,
  isFollowingVehicle,
  onCancelFollowVehicle,
  selectedTrainService,
  onSelectTrainService,
  onClearTrainService,
  trainServiceResult,
}: Props) {
  // Precedence: trainService > busService > route > stop > station
  let panelTitle: string
  let ariaLabel: string

  if (selectedTrainService) {
    const data = trainServiceResult.data
    if (data?.found) {
      panelTitle = `${data.origin} → ${data.destination}`
      ariaLabel = `Train service detail: ${data.origin} to ${data.destination}`
    } else {
      panelTitle = `Train at ${selectedTrainService.scheduledTime}`
      ariaLabel = 'Train service detail'
    }
  } else if (selectedService) {
    panelTitle = `Service ${selectedService.line} → ${selectedService.destination}`
    ariaLabel = `Service detail: ${selectedService.line} to ${selectedService.destination}`
  } else if (selectedRoute) {
    panelTitle = `Route ${selectedRoute.shortName}`
    ariaLabel = `Route detail: ${selectedRoute.shortName}`
  } else if (selectedStop) {
    panelTitle = selectedStop.shortName
      ? `${selectedStop.name} | ${selectedStop.shortName}`
      : selectedStop.name
    ariaLabel = `${isRailStopType(selectedStop.stopType) ? 'Train station board' : 'Bus stop board'}: ${selectedStop.name}`
  } else {
    panelTitle = `${MELTON_STATION.name} | ${MELTON_STATION.crs}`
    ariaLabel = 'Live train board'
  }

  return (
    <aside
      className={`${styles.panel} ${collapsed ? styles.panelCollapsed : ''}`}
      aria-label={ariaLabel}
    >
      <div className={`${styles.headerCard} glass-panel`}>
        <div className={styles.panelHandle}>
          <h2 className={styles.panelTitle}>{panelTitle}</h2>
          <div className={styles.panelHandleActions}>
            {selectedTrainService && (
              <button
                className={styles.backBtn}
                onClick={onClearTrainService}
                aria-label="Back to station board"
              >
                &larr; Stop
              </button>
            )}
            {!selectedTrainService && selectedService && (
              <button
                className={styles.backBtn}
                onClick={onClearService}
                aria-label={isLiveVehicle ? 'Stop tracking this bus' : 'Back to stop departures'}
              >
                &larr; {isLiveVehicle ? 'Close' : 'Stop'}
              </button>
            )}
            {!selectedTrainService && !selectedService && selectedStop && (
              <button
                className={styles.closeBtn}
                onClick={onClearStop}
                aria-label="Close stop board"
                title="Close"
              >
                &times;
              </button>
            )}
            {!selectedTrainService && !selectedService && !selectedStop && selectedRoute && (
              <button
                className={styles.closeBtn}
                onClick={onClearRoute}
                aria-label="Close route"
                title="Close"
              >
                &times;
              </button>
            )}
            <button
              className={styles.collapseBtn}
              onClick={onToggleCollapse}
              aria-label={collapsed ? 'Expand board' : 'Collapse board'}
              title={collapsed ? 'Expand board' : 'Collapse board'}
            >
              {collapsed ? '▲' : '▼'}
            </button>
          </div>
        </div>
      </div>

      {!collapsed && (
        <div className={`${styles.timetableCard} glass-panel`}>
          <div className={styles.boardContent}>
            {/* Keyed wrappers force a remount on view change, replaying the slide-in. */}
            {selectedTrainService ? (
              <div
                key={`train-${selectedTrainService.crs}-${selectedTrainService.scheduledTime}-${selectedTrainService.destination}`}
                className={`${styles.viewTransition} ${styles.viewTransitionFill}`}
              >
                <TrainServiceDetail
                  serviceResult={trainServiceResult}
                  onFocusStop={onFocusStop}
                />
              </div>
            ) : selectedService ? (
              <div
                key={`service-${selectedService.tripId}`}
                className={`${styles.viewTransition} ${styles.viewTransitionFill}`}
              >
                <ServiceDetail
                  service={selectedService}
                  stopsResult={serviceStops}
                  onFocusStop={onFocusStop}
                  isLiveVehicle={isLiveVehicle}
                  liveVehicleId={liveVehicleId}
                  liveLocating={liveLocating}
                  liveUnavailable={liveUnavailable}
                  onLocateVehicle={onLocateVehicle}
                  onRecenterVehicle={onRecenterVehicle}
                  isFollowing={isFollowingVehicle}
                  onCancelFollow={onCancelFollowVehicle}
                />
              </div>
            ) : selectedRoute ? (
              <div key={`route-${selectedRoute.shortName}`} className={styles.viewTransition}>
                <RoutePanel route={selectedRoute} onFocusStop={onFocusStop} />
              </div>
            ) : selectedStop ? (
              <div key={`stop-${selectedStop.atcoCode}`} className={styles.viewTransition}>
                <StopBoardView
                  stop={selectedStop}
                  onSelectService={onSelectService}
                  onSelectTrainService={onSelectTrainService}
                />
              </div>
            ) : (
              <div key="station" className={styles.viewTransition}>
                <StationBoardView
                  crs={MELTON_STATION.crs}
                  onSelectTrainService={onSelectTrainService}
                />
              </div>
            )}
          </div>
        </div>
      )}
    </aside>
  )
}
