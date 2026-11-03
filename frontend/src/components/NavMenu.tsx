import { useEffect, useRef, useState } from 'react'
import styles from './NavMenu.module.css'

interface NavItem {
  label: string
  icon: string
  href: string
}

const NAV_ITEMS: NavItem[] = [
  { label: 'Map', icon: '🗺️', href: window.location.pathname },
  { label: 'Data Sources', icon: '📡', href: '?view=data-sources' },
  { label: 'About', icon: 'ℹ️', href: '?view=about' },
  { label: 'Contact', icon: '✉️', href: '?view=contact' },
]

const TV_ITEM: NavItem = { label: 'TV display', icon: '📺', href: '?view=tv' }

interface NavMenuProps {
  /**
   * 'floating' (default) pins the burger top-right of its positioned container — used on the
   * sub-pages. 'inline' lets it flow inside another element (e.g. the map's brand pill, where a
   * top-right pin would collide with Leaflet's zoom controls).
   */
  placement?: 'floating' | 'inline'
}

/**
 * Burger nav menu. See {@link NavMenuProps.placement} for the two layouts.
 */
export function NavMenu({ placement = 'floating' }: NavMenuProps) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const inline = placement === 'inline'

  // Close on outside click
  useEffect(() => {
    if (!open) return
    function handleClick(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [open])

  // Close on Escape
  useEffect(() => {
    if (!open) return
    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', handleKey)
    return () => document.removeEventListener('keydown', handleKey)
  }, [open])

  return (
    <div ref={rootRef} className={inline ? styles.rootInline : styles.root}>
      <button
        className={styles.trigger}
        aria-label="Open navigation menu"
        aria-expanded={open}
        aria-haspopup="menu"
        onClick={() => setOpen((v) => !v)}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <line x1="4" y1="7" x2="20" y2="7" />
          <line x1="4" y1="12" x2="20" y2="12" />
          <line x1="4" y1="17" x2="20" y2="17" />
        </svg>
      </button>

      {open && (
        <nav
          role="menu"
          aria-label="Site navigation"
          className={inline ? `${styles.dropdown} ${styles.dropdownLeft}` : styles.dropdown}
        >
          {NAV_ITEMS.map((item) => (
            <a
              key={item.href}
              href={item.href}
              role="menuitem"
              className={styles.item}
              onClick={() => setOpen(false)}
            >
              <span className={styles.itemIcon} aria-hidden="true">{item.icon}</span>
              {item.label}
            </a>
          ))}
          <div className={styles.divider} role="separator" />
          <a
            href={TV_ITEM.href}
            role="menuitem"
            className={styles.item}
            onClick={() => setOpen(false)}
          >
            <span className={styles.itemIcon} aria-hidden="true">{TV_ITEM.icon}</span>
            {TV_ITEM.label}
          </a>
        </nav>
      )}
    </div>
  )
}
