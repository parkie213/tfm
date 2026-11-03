import { DataSourceStatus } from '../proto/tfm/v1/data_sources_pb'
import styles from './DataSourceBadge.module.css'

interface Props {
  status: DataSourceStatus
  /** The backend's explanation (endpoint, missing key, last error); shown on hover. */
  detail?: string
}

interface BadgeConfig {
  label: string
  variant: 'live' | 'scaffolded' | 'muted' | 'error'
}

function resolve(status: DataSourceStatus): BadgeConfig {
  switch (status) {
    case DataSourceStatus.LIVE:
      return { label: 'Live', variant: 'live' }
    case DataSourceStatus.SCAFFOLDED:
      return { label: 'Waiting for data', variant: 'scaffolded' }
    case DataSourceStatus.DEFERRED:
      return { label: 'Planned', variant: 'muted' }
    case DataSourceStatus.ERROR:
      return { label: 'Error', variant: 'error' }
    case DataSourceStatus.DISABLED:
      return { label: 'Off', variant: 'muted' }
    default:
      return { label: 'Unknown', variant: 'muted' }
  }
}

export function DataSourceBadge({ status, detail }: Props) {
  const { label, variant } = resolve(status)
  const title =
    detail || (status === DataSourceStatus.DISABLED ? 'Not enabled on this server' : undefined)
  return (
    <span className={`${styles.badge} ${styles[variant]}`} title={title}>
      {label}
    </span>
  )
}
