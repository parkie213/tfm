import type { DataSource } from '../proto/tfm/v1/data_sources_pb'
import { PageShell } from '../components/PageShell'
import { DataSourceBadge } from '../components/DataSourceBadge'
import { useDataSources } from '../hooks/useDataSources'
import { useNowTick } from '../hooks/useNowTick'
import { freshness, formatAgo } from '../lib/freshness'
import type { FreshnessTone } from '../lib/freshness'
import styles from './DataSourcesView.module.css'

// ---- Category display order ----

const CATEGORY_ORDER = ['Trains', 'Buses', 'Geography & routing', 'Reference data']

// ---- Helpers ----

function formatCount(n: bigint): string {
  const num = Number(n)
  if (num < 0) return ''
  return num.toLocaleString()
}

function dotClass(tone: FreshnessTone): string {
  switch (tone) {
    case 'green': return styles.dotGreen
    case 'amber': return styles.dotAmber
    case 'red': return styles.dotRed
    default: return styles.dotGrey
  }
}

// ---- Sub-components ----

interface SourceRowProps {
  source: DataSource
  nowMs: number
}

function SourceRow({ source, nowMs }: SourceRowProps) {
  const f = freshness(source.lastUpdated, source.expectedIntervalSecs, nowMs)
  const countStr = formatCount(source.itemCount)

  return (
    <div className={styles.row}>
      <div className={styles.rowLeft}>
        <div className={styles.sourceName}>{source.name}</div>
        {source.feeds && <div className={styles.feeds}>{source.feeds}</div>}
      </div>

      <div className={styles.freshness} title={f.absTitle}>
        <span className={`${styles.dot} ${dotClass(f.tone)}`} aria-hidden="true" />
        {f.relLabel}
      </div>

      <DataSourceBadge status={source.status} detail={source.detail} />

      {countStr ? (
        <div className={styles.count}>{countStr}</div>
      ) : (
        <div className={styles.count} />
      )}
    </div>
  )
}

interface CategoryGroupProps {
  category: string
  sources: DataSource[]
  nowMs: number
}

function CategoryGroup({ category, sources, nowMs }: CategoryGroupProps) {
  if (sources.length === 0) return null
  return (
    <section className={styles.group}>
      <h2 className={styles.groupTitle}>{category}</h2>
      <div className={`${styles.card} glass-panel`}>
        {sources.map((s) => (
          <SourceRow key={s.id} source={s} nowMs={nowMs} />
        ))}
      </div>
    </section>
  )
}

// ---- Main view ----

export default function DataSourcesView() {
  const { sources, lastUpdated, loading, error } = useDataSources()
  const nowMs = useNowTick(1_000)

  // Group sources by category, preserving the defined order
  const grouped = new Map<string, DataSource[]>()
  for (const cat of CATEGORY_ORDER) grouped.set(cat, [])
  for (const s of sources) {
    const bucket = grouped.get(s.category)
    if (bucket) {
      bucket.push(s)
    } else {
      // Unknown category — add it
      grouped.set(s.category, [s])
    }
  }

  return (
    <PageShell>
      <h1 className={styles.heading}>Data Sources</h1>
      <p className={styles.subheading}>
        Live status of every feed and dataset the app depends on.
      </p>
      {lastUpdated && (
        <p className={styles.updatedAt}>
          Updated {formatAgo(lastUpdated.getTime(), nowMs)}
        </p>
      )}

      {error && (
        <div className={styles.errorBox} role="alert">
          Could not load data sources: {error}
        </div>
      )}

      {loading && sources.length === 0 ? (
        <div className={`${styles.stateBox} glass-panel`}>Loading&hellip;</div>
      ) : (
        <>
          {CATEGORY_ORDER.map((cat) => (
            <CategoryGroup
              key={cat}
              category={cat}
              sources={grouped.get(cat) ?? []}
              nowMs={nowMs}
            />
          ))}
          {/* Render any server-side categories not in our order list */}
          {[...grouped.entries()]
            .filter(([cat]) => !CATEGORY_ORDER.includes(cat))
            .map(([cat, srcs]) => (
              <CategoryGroup key={cat} category={cat} sources={srcs} nowMs={nowMs} />
            ))}
        </>
      )}
    </PageShell>
  )
}
