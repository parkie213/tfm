import { StrictMode, lazy, Suspense } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App'

// Code-split each full-page view so the normal app doesn't pay for them.
// Activated via ?view=<name> (e.g. a kiosk URL for TV, or nav links for the sub-pages).
const TVView = lazy(() => import('./pages/TVView'))
const DataSourcesView = lazy(() => import('./pages/DataSourcesView'))
const AboutView = lazy(() => import('./pages/AboutView'))
const ContactView = lazy(() => import('./pages/ContactView'))

const view = new URLSearchParams(window.location.search).get('view')

const root = document.getElementById('root')
if (!root) throw new Error('Root element not found')

function Root() {
  switch (view) {
    case 'tv':
      return <TVView />
    case 'data-sources':
      return <DataSourcesView />
    case 'about':
      return <AboutView />
    case 'contact':
      return <ContactView />
    default:
      return <App />
  }
}

createRoot(root).render(
  <StrictMode>
    <Suspense fallback={null}>
      <Root />
    </Suspense>
  </StrictMode>,
)
