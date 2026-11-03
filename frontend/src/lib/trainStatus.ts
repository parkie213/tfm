import type { Departure } from '../proto/tfm/v1/boards_pb'

/** TV train status: an unquantified Darwin delay is still a delay. */
export function trainStatus(d: Pick<Departure, 'status' | 'delayMinutes'>): {
  text: string
  tone: 'on' | 'late' | 'cancelled'
} {
  const status = d.status.toLowerCase()
  if (status === 'cancelled') return { text: 'Cancelled', tone: 'cancelled' }
  if (d.delayMinutes > 0) return { text: `${d.delayMinutes} min late`, tone: 'late' }
  if (status === 'delayed') return { text: 'Delayed', tone: 'late' }
  return { text: 'On time', tone: 'on' }
}
