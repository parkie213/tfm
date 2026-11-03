import { useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent, ReactNode } from 'react'
import styles from './BottomSheet.module.css'

export type SheetSnap = 'peek' | 'half' | 'full'

// Exposed height at the 'peek' snap — grabber + panel title row.
const PEEK_PX = 76
// The sheet element itself spans the bottom 88% of the viewport (see module CSS).
const SHEET_VH = 0.88
const MOBILE_QUERY = '(max-width: 700px)'
const SNAPS: SheetSnap[] = ['full', 'half', 'peek']

function yForSnap(snap: SheetSnap): number {
  const h = window.innerHeight
  const sheetH = h * SHEET_VH
  if (snap === 'full') return 0
  if (snap === 'half') return sheetH - h * 0.5
  return sheetH - PEEK_PX
}

function prefersReducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

interface Props {
  children: ReactNode
  className?: string
  onSnapChange?: (snap: SheetSnap) => void
  // External request to move the sheet (nonce re-fires repeat requests).
  snapRequest?: { snap: SheetSnap; nonce: number } | null
}

/**
 * Mobile draggable bottom sheet with peek / half / full snap points.
 * On desktop (>700px) it renders as a plain flex column filling its grid cell —
 * all sheet behaviour lives behind the mobile media query + matchMedia checks.
 *
 * Drags are grabber-only (pointer capture), so Leaflet never sees them and
 * the board content keeps its native scroll.
 */
export function BottomSheet({ children, className, onSnapChange, snapRequest }: Props) {
  const sheetRef = useRef<HTMLDivElement>(null)
  const [snap, setSnap] = useState<SheetSnap>('half')
  const snapState = useRef<{ snap: SheetSnap; y: number }>({ snap: 'half', y: 0 })
  const dragRef = useRef<{ pointerId: number; startY: number; startTranslate: number; moved: boolean } | null>(null)
  // Browsers fire a click after pointerup even when the pointer moved (capture
  // keeps the target) — this flag stops a drag from also cycling the snap.
  const suppressClickRef = useRef(false)

  function applyY(y: number, animate: boolean) {
    const el = sheetRef.current
    if (!el) return
    el.style.transition = animate && !prefersReducedMotion()
      ? 'transform 0.32s cubic-bezier(0.22, 1, 0.36, 1)'
      : 'none'
    el.style.transform = `translateY(${y}px)`
    snapState.current.y = y
  }

  function snapTo(next: SheetSnap, animate = true) {
    applyY(yForSnap(next), animate)
    if (snapState.current.snap !== next) {
      snapState.current.snap = next
      setSnap(next)
      onSnapChange?.(next)
    }
  }

  // Position on mount / breakpoint change / resize; clear inline styles on desktop
  // so the media-query layout takes over untouched.
  useEffect(() => {
    const mql = window.matchMedia(MOBILE_QUERY)

    function sync() {
      const el = sheetRef.current
      if (!el) return
      if (mql.matches) {
        applyY(yForSnap(snapState.current.snap), false)
      } else {
        el.style.transform = ''
        el.style.transition = ''
      }
    }

    sync()
    mql.addEventListener('change', sync)
    window.addEventListener('resize', sync)
    return () => {
      mql.removeEventListener('change', sync)
      window.removeEventListener('resize', sync)
    }
  }, [])

  // Obey external snap requests (e.g. "reveal the map" after a stop is focused).
  useEffect(() => {
    if (snapRequest && window.matchMedia(MOBILE_QUERY).matches) {
      snapTo(snapRequest.snap)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snapRequest?.nonce])

  function handlePointerDown(e: ReactPointerEvent<HTMLButtonElement>) {
    e.currentTarget.setPointerCapture(e.pointerId)
    dragRef.current = {
      pointerId: e.pointerId,
      startY: e.clientY,
      startTranslate: snapState.current.y,
      moved: false,
    }
  }

  function handlePointerMove(e: ReactPointerEvent<HTMLButtonElement>) {
    const drag = dragRef.current
    if (!drag || e.pointerId !== drag.pointerId) return
    const dy = e.clientY - drag.startY
    if (Math.abs(dy) > 6) drag.moved = true
    const max = window.innerHeight * SHEET_VH - PEEK_PX
    applyY(Math.min(max, Math.max(0, drag.startTranslate + dy)), false)
  }

  function handlePointerEnd(e: ReactPointerEvent<HTMLButtonElement>) {
    const drag = dragRef.current
    if (!drag || e.pointerId !== drag.pointerId) return
    dragRef.current = null
    // No real movement → let the click handler cycle snaps instead.
    if (!drag.moved) return
    suppressClickRef.current = true
    // Settle on the nearest snap point.
    const y = snapState.current.y
    let best: SheetSnap = 'half'
    let bestDist = Infinity
    for (const candidate of SNAPS) {
      const dist = Math.abs(yForSnap(candidate) - y)
      if (dist < bestDist) {
        bestDist = dist
        best = candidate
      }
    }
    snapTo(best)
  }

  // Tap / keyboard: cycle peek → half → full → peek.
  function handleGrabberClick() {
    if (suppressClickRef.current) {
      suppressClickRef.current = false
      return
    }
    const current = snapState.current.snap
    const next: SheetSnap = current === 'peek' ? 'half' : current === 'half' ? 'full' : 'peek'
    snapTo(next)
  }

  return (
    <div ref={sheetRef} className={`${styles.sheet} ${className ?? ''}`}>
      <button
        type="button"
        className={styles.grabber}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerEnd}
        onPointerCancel={handlePointerEnd}
        onClick={handleGrabberClick}
        aria-label={`Departure board panel (${snap}) — drag or activate to resize`}
      >
        <span className={styles.grabberBar} aria-hidden="true" />
      </button>
      {children}
    </div>
  )
}
