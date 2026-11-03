import { PageShell } from '../components/PageShell'
import styles from './ContactView.module.css'

export default function ContactView() {
  return (
    <PageShell>
      <h1 className={styles.heading}>Contact</h1>

      <div className={`${styles.card} glass-panel`}>
        <p>
          Spotted a wrong stop or time? Got a suggestion? We'd love to hear it.
        </p>
        <p>
          When reporting a problem, the stop name, route number, and roughly when you saw it
          all help us track it down. A proper feedback channel is coming soon.
        </p>
      </div>
    </PageShell>
  )
}
