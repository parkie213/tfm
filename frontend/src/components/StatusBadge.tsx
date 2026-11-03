import styles from './StatusBadge.module.css'

interface Props {
  status: string
  isLive: boolean
}

type StatusKey = 'on-time' | 'delayed' | 'cancelled' | 'due' | 'scheduled'

function resolveStatus(status: string, isLive: boolean): StatusKey {
  const s = status.toLowerCase()
  if (s === 'on time') return 'on-time'
  if (s === 'delayed') return 'delayed'
  if (s === 'cancelled') return 'cancelled'
  if (s === 'due') return 'due'
  if (!isLive) return 'scheduled'
  return 'due'
}

export function StatusBadge({ status, isLive }: Props) {
  const key = resolveStatus(status, isLive)
  return (
    <span className={`${styles.badge} ${styles[key]}`}>
      {status}
    </span>
  )
}
