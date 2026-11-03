import type { Coach } from '../proto/tfm/v1/boards_pb'
import styles from './CoachLoading.module.css'

interface Props {
  coaches: Coach[]
}

function loadingColour(loading: number, loadingKnown: boolean): string {
  if (!loadingKnown) return styles.loadingUnknown
  if (loading > 66) return styles.loadingHigh
  if (loading >= 34) return styles.loadingMid
  return styles.loadingLow
}

function hasToilet(toilet: string): boolean {
  const t = toilet.toLowerCase()
  return t !== '' && t !== 'none' && t !== 'unknown'
}

function isFirstClass(coachClass: string): boolean {
  return coachClass.toLowerCase() === 'first'
}

export function CoachLoading({ coaches }: Props) {
  if (coaches.length === 0) return null

  return (
    <div className={styles.strip} role="list" aria-label="Train formation">
      {coaches.map((coach) => (
        <div
          key={coach.number}
          role="listitem"
          className={`${styles.coach} ${loadingColour(coach.loading, coach.loadingKnown)}`}
          title={
            coach.loadingKnown
              ? `Coach ${coach.number}: ${coach.loading}% full`
              : `Coach ${coach.number}: loading unknown`
          }
        >
          <span className={styles.coachNumber}>{coach.number}</span>

          <div className={styles.coachIcons}>
            {isFirstClass(coach.coachClass) && (
              <span className={styles.firstClass} aria-label="First class">1</span>
            )}
            {hasToilet(coach.toilet) && (
              <span className={styles.toilet} aria-label={`Toilet (${coach.toilet})`}>
                {coach.toilet === 'Accessible' ? '♿' : '🚽'}
              </span>
            )}
          </div>

          <div className={styles.loadingBarOuter}>
            <div
              className={`${styles.loadingBarInner} ${loadingColour(coach.loading, coach.loadingKnown)}`}
              style={{ width: coach.loadingKnown ? `${coach.loading}%` : '100%' }}
            />
          </div>

          {coach.loadingKnown && (
            <span className={styles.loadingPct}>{coach.loading}%</span>
          )}
        </div>
      ))}
    </div>
  )
}
