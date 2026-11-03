import type { ReactNode } from 'react'
import { TfmLogo } from './TfmLogo'
import { NavMenu } from './NavMenu'
import styles from './PageShell.module.css'

interface Props {
  children: ReactNode
}

/**
 * Shared chrome wrapper for sub-pages (Data Sources, About, Contact).
 * Provides a brand header with a back-to-map link and the NavMenu.
 * Children render inside a max-width centred content area.
 */
export function PageShell({ children }: Props) {
  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <a href={window.location.pathname} className={styles.brand} aria-label="Transport for Melton — back to map">
          <TfmLogo size={32} color="var(--brand)" amplitude={0.14} />
          <div className={styles.brandText}>
            <div className={styles.brandName}>Transport for Melton</div>
            <div className={styles.brandTagline}>powered by pies &#x1F967;</div>
          </div>
        </a>
        <a href={window.location.pathname} className={styles.backLink}>
          ← Map
        </a>
        {/* NavMenu is absolute-positioned top-right within this relative header */}
        <NavMenu />
      </header>
      <main className={styles.content}>{children}</main>
    </div>
  )
}
