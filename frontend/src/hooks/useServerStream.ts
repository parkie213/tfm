import { useEffect, useRef, useState } from 'react'
import { ConnectError, Code } from '@connectrpc/connect'

const DEFAULT_RECONNECT_BASE_MS = 2_000
const DEFAULT_RECONNECT_MAX_MS = 30_000
// Don't flash an error on a single transient blip (a clean stream end, a proxy bounce). Only surface
// `error` once this many consecutive attempts have failed — by then it's a sustained outage worth
// telling the user about, not a reconnect they'd never notice.
const SURFACE_ERROR_AFTER_ATTEMPTS = 2

export interface ServerStreamOptions<T> {
  /** When false, no stream is opened (e.g. no viewport / zoomed out). */
  enabled: boolean
  /**
   * Re-subscribe — tear down + reopen, firing {@link onReset} — whenever any dep changes. Keep these
   * COARSE: gRPC-Web has no client/bidi streaming, so a new subscription (e.g. a new viewport) means a
   * full reopen. Snap the viewport to a grid (see `lib/viewportRegion`) so this fires rarely.
   */
  deps: ReadonlyArray<unknown>
  /** Opens the server-stream. Must pass the AbortSignal through to the RPC call. */
  makeStream: (signal: AbortSignal) => AsyncIterable<T>
  /** Handles one streamed message. */
  onMessage: (msg: T) => void
  /** Fired at the start of every (re)subscription generation — clear per-subscription accumulators. */
  onReset?: () => void
  reconnectBaseMs?: number
  reconnectMaxMs?: number
}

/**
 * Drives a long-lived gRPC server-stream with the lifecycle every streaming hook needs, so individual
 * feed hooks don't each re-implement (and subtly mis-implement) it:
 *
 * <ul>
 *   <li>opens on mount, re-subscribes when {@link ServerStreamOptions.deps} change;</li>
 *   <li>reconnects with capped exponential backoff on a transient error / stream end;</li>
 *   <li>aborts cleanly on unmount or dep-change (no leaked readers);</li>
 *   <li>treats gRPC UNIMPLEMENTED as terminal (the feature is off server-side) and surfaces it as
 *       {@code unavailable} so the caller can hide the layer quietly — no retry storm;</li>
 *   <li>surfaces a sustained connection failure as {@code error} (after a few failed attempts, cleared
 *       on the next delivered message) so the caller can replace an eternal skeleton with a message
 *       instead of spinning forever. Both latches reset when {@code deps} change (a fresh subscription
 *       key — new CRS/viewport — deserves a clean attempt; a visibility toggle alone does not).</li>
 * </ul>
 *
 * `makeStream`/`onMessage`/`onReset` are read through a ref so passing fresh inline closures each render
 * does NOT re-subscribe; only `deps`/`enabled` do. `makeStream` therefore closes over the latest values,
 * which is consistent because the bbox (etc.) it reads is also in `deps`.
 */
export function useServerStream<T>(opts: ServerStreamOptions<T>): { unavailable: boolean; error: string | null } {
  const [unavailable, setUnavailable] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const unavailableRef = useRef(false)
  const cbRef = useRef(opts)
  cbRef.current = opts

  const base = opts.reconnectBaseMs ?? DEFAULT_RECONNECT_BASE_MS
  const max = opts.reconnectMaxMs ?? DEFAULT_RECONNECT_MAX_MS

  // A new subscription key (CRS/viewport changed) deserves a clean attempt: clear both the terminal
  // UNIMPLEMENTED latch and any surfaced error BEFORE the subscription effect below re-runs. Keyed on
  // `deps` ONLY — a visibility toggle (which re-runs the subscription effect via `shouldRun`) must NOT
  // reset, or a feature that's genuinely off server-side would be re-probed on every tab focus.
  // Declared first so its commit runs before the subscription effect's, which reads `unavailableRef`.
  useEffect(() => {
    unavailableRef.current = false
    setUnavailable(false)
    setError(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, opts.deps)

  // Suspend the stream while the tab is hidden: a held server-stream otherwise keeps a connection open
  // (one of ~6 on HTTP/1.1) and keeps the server fanning frames to a screen nobody's looking at. On
  // re-show we re-subscribe and get a fresh frame immediately.
  const visible = useDocumentVisible()
  const shouldRun = opts.enabled && visible

  // Distinguish a genuine new subscription (CRS/viewport changed) from a mere reconnect — a tab
  // re-show re-runs this effect with the SAME deps. `onReset` (which clears data + shows a skeleton)
  // must fire only on the former; on a resume we keep the last data on screen and swap it for the
  // fresh frame when it arrives (stale-while-revalidate), with no skeleton flash. deps are primitives.
  const depsKey = JSON.stringify(opts.deps)
  const lastDepsKeyRef = useRef<string | null>(null)

  useEffect(() => {
    if (!shouldRun || unavailableRef.current) return

    let active = true
    let attempt = 0
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    const controller = new AbortController()
    if (lastDepsKeyRef.current !== depsKey) {
      lastDepsKeyRef.current = depsKey
      cbRef.current.onReset?.()
    }

    async function run() {
      while (active) {
        let failure: string | null = null
        try {
          for await (const msg of cbRef.current.makeStream(controller.signal)) {
            if (!active) break
            cbRef.current.onMessage(msg)
            attempt = 0 // a delivered message proves the stream is healthy → reset backoff
            setError(null) // …and clears any error we were showing (no-op re-render if already null)
          }
        } catch (err) {
          if (!active || (err instanceof Error && err.name === 'AbortError')) return
          if (err instanceof ConnectError && err.code === Code.Unimplemented) {
            unavailableRef.current = true
            setUnavailable(true)
            return // feature disabled server-side — give up quietly, no reconnect
          }
          // transient (network / proxy bounce) → record it and fall through to backoff
          failure = friendlyStreamError(err)
        }
        if (!active) return
        // A clean stream end (no throw) is a normal reconnect — only a real failure counts toward
        // surfacing an error, and only once it's persisted past a couple of attempts.
        if (failure && attempt >= SURFACE_ERROR_AFTER_ATTEMPTS) setError(failure)
        const delay = Math.min(base * 2 ** attempt, max)
        attempt++
        await new Promise<void>((resolve) => { reconnectTimer = setTimeout(resolve, delay) })
      }
    }
    run()

    return () => {
      active = false
      controller.abort()
      if (reconnectTimer) clearTimeout(reconnectTimer)
    }
    // Re-subscribe on shouldRun(enabled+visible)/deps change only; callbacks are read via cbRef.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shouldRun, ...opts.deps])

  return { unavailable, error }
}

/**
 * A short, user-facing message for a stream failure. Deliberately generic — never leaks the raw
 * upstream text (e.g. ConnectError's "[unavailable] LDBWS returned HTTP 400: …"), which is noise to a
 * rider. The stream keeps retrying underneath regardless of what we show.
 */
function friendlyStreamError(_err: unknown): string {
  return 'Live updates are unavailable right now — retrying…'
}

/** Tracks document visibility (true while the tab is shown). SSR-safe default of visible. */
function useDocumentVisible(): boolean {
  const [visible, setVisible] = useState(
    () => typeof document === 'undefined' || document.visibilityState !== 'hidden',
  )
  useEffect(() => {
    const onChange = () => setVisible(document.visibilityState !== 'hidden')
    document.addEventListener('visibilitychange', onChange)
    return () => document.removeEventListener('visibilitychange', onChange)
  }, [])
  return visible
}
