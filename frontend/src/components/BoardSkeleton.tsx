import type { CSSProperties } from 'react'
import styles from './BoardSkeleton.module.css'

interface Props {
  // 'train' mirrors the 7-column train board; 'bus' the 4-column bus board.
  variant?: 'train' | 'bus'
  rows?: number
  // Set false where the surrounding view already renders its own header.
  header?: boolean
}

/** Shimmer placeholder matching the departure-table shape, shown while a board loads. */
export function BoardSkeleton({ variant = 'train', rows = 6, header = true }: Props) {
  const isTrain = variant === 'train'
  return (
    <div className={styles.skeleton} role="status" aria-busy="true" aria-label="Loading departures">
      {header && <div className={styles.headerShimmer} />}
      <div className={styles.tableShimmer}>
        {Array.from({ length: rows }, (_, i) => (
          <div key={i} className={styles.row} style={{ '--row-i': i } as CSSProperties}>
            <div className={`${styles.cell} ${styles.cellTime}`} />
            {isTrain && <div className={`${styles.cell} ${styles.cellExpected}`} />}
            {!isTrain && <div className={`${styles.cell} ${styles.cellLine}`} />}
            <div className={`${styles.cell} ${styles.cellDest}`} />
            {isTrain && (
              <>
                <div className={`${styles.cell} ${styles.cellPlatform}`} />
                <div className={`${styles.cell} ${styles.cellStatus}`} />
              </>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
