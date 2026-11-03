import { PageShell } from '../components/PageShell'
import styles from './AboutView.module.css'

export default function AboutView() {
  return (
    <PageShell>
      <h1 className={styles.heading}>About</h1>

      <div className={`${styles.card} glass-panel`}>
        <h2>What is Transport for Melton?</h2>
        <p>
          Transport for Melton (TfM) is a live bus and train departure board for Melton Mowbray
          and the surrounding area. It shows real-time vehicle positions, up-to-the-minute departure
          times, and route maps — all in one place.
        </p>
        <p>
          Bus data comes from the Bus Open Data Service (BODS) and is updated every 30 seconds.
          Train data comes from Darwin (the National Rail real-time data platform) and updates
          every few seconds. Route geometry is derived from the GTFS timetable feed.
        </p>
        <p>
          TfM is a personal project. It is not affiliated with any transport operator or local
          authority. Powered by pies &#x1F967;
        </p>
      </div>

      <div className={`${styles.card} glass-panel`}>
        <h2>Data attributions &amp; licences</h2>
        <ul className={styles.licenceList}>
          <li>
            Bus timetable and stop data:{' '}
            <a
              href="https://www.bus-data.dft.gov.uk/"
              target="_blank"
              rel="noopener noreferrer"
            >
              Bus Open Data Service (BODS)
            </a>{' '}
            &mdash; licensed under the{' '}
            <a
              href="https://www.nationalarchives.gov.uk/doc/open-government-licence/"
              target="_blank"
              rel="noopener noreferrer"
            >
              Open Government Licence v3.0 (OGL)
            </a>
          </li>
          <li>
            National stop and locality data:{' '}
            <a
              href="https://www.data.gov.uk/dataset/ff93ffc1-6d58-4a6e-b537-4af99f774e82/national-public-transport-access-nodes-naptan"
              target="_blank"
              rel="noopener noreferrer"
            >
              NaPTAN
            </a>{' '}
            &mdash; Open Government Licence v3.0 (OGL)
          </li>
          <li>
            Live train departures and arrivals:{' '}
            <a
              href="https://www.raildeliverygroup.com/our-products/rail-data/rail-data-marketplace.html"
              target="_blank"
              rel="noopener noreferrer"
            >
              Rail Data Marketplace (RDM) &mdash; Darwin / LDBWS
            </a>
          </li>
          <li>
            Map tiles and background geography:{' '}
            <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">
              &copy; OpenStreetMap contributors
            </a>{' '}
            &mdash; licensed under the{' '}
            <a href="https://opendatacommons.org/licenses/odbl/" target="_blank" rel="noopener noreferrer">
              ODbL
            </a>
          </li>
        </ul>
      </div>
    </PageShell>
  )
}
