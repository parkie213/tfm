import styles from './NearMeButton.module.css'

interface Props {
  loading: boolean
  onClick: () => void
}

/** "What can I catch near me?" — locates the device and opens a merged board of nearby departures. */
export function NearMeButton({ loading, onClick }: Props) {
  return (
    <button
      className={`${styles.btn} glass-panel`}
      onClick={onClick}
      disabled={loading}
      title="Departures from stops near you"
    >
      <span aria-hidden="true">🧭</span> {loading ? 'Locating…' : 'Near me'}
    </button>
  )
}
