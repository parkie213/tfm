import { useLayoutEffect, useRef, type RefObject } from 'react'

/**
 * FLIP reorder animation for a list whose items change order between renders.
 *
 * Pass the container ref and the ordered list of stable keys. Every direct child of the container
 * must carry a `data-flip-key` attribute matching one of those keys (and a matching React `key`, so
 * React moves the DOM node rather than recreating it). When the order changes, each row slides from
 * where it used to be to its new position.
 *
 * Uses `offsetTop` (a layout measurement that ignores transforms) so an interrupted animation can't
 * feed a mid-flight visual position back in. Honours `prefers-reduced-motion`.
 */
export function useFlipReorder(
  containerRef: RefObject<HTMLElement | null>,
  order: string[],
  durationMs = 450,
) {
  const prevTops = useRef<Map<string, number>>(new Map())

  useLayoutEffect(() => {
    const container = containerRef.current
    if (!container) return

    const reduceMotion =
      typeof window !== 'undefined' &&
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

    const children = Array.from(container.children) as HTMLElement[]
    const nextTops = new Map<string, number>()
    const moved: HTMLElement[] = []

    for (const child of children) {
      const key = child.dataset.flipKey
      if (!key) continue
      const top = child.offsetTop
      nextTops.set(key, top)

      const prev = prevTops.current.get(key)
      const dy = prev === undefined ? 0 : prev - top
      if (!reduceMotion && Math.abs(dy) >= 1) {
        // Invert: snap the row back to its old spot with no transition, ready to play forward.
        child.style.transition = 'none'
        child.style.transform = `translateY(${dy}px)`
        moved.push(child)
      } else if (child.style.transform) {
        // No movement this round — clear any transform left over from an interrupted animation.
        child.style.transition = 'none'
        child.style.transform = ''
      }
    }

    prevTops.current = nextTops
    if (moved.length === 0) return

    // Play on the next frame: release to the natural position with a transition.
    const raf = requestAnimationFrame(() => {
      for (const child of moved) {
        child.style.transition = `transform ${durationMs}ms cubic-bezier(0.22, 1, 0.36, 1)`
        child.style.transform = ''
      }
    })
    return () => cancelAnimationFrame(raf)
  }, [order.join('|'), containerRef, durationMs])
}
