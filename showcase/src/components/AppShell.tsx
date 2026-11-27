import type { ReactNode } from 'react';
import { Navigation } from './Navigation';

export function AppShell({ children }: { children: ReactNode }) {
  return <>
    <a className="skip-link" href="#main">Skip to content</a>
    <aside className="sidebar">
      <a className="brand" href="/"><span className="brand-mark" aria-hidden="true">CC</span><span>CommerceCore<small>Technical showcase</small></span></a>
      <Navigation />
      <p className="sidebar-note">Correctness boundaries.<br />Explicit evidence.</p>
    </aside>
    <div className="workspace">
      <div className="topbar"><span>CommerceCore / Showcase</span><span className="status">Local browser simulation · C2</span></div>
      <main id="main" tabIndex={-1}>{children}
        <section className="boundary" aria-labelledby="boundary-title"><h2 id="boundary-title">Execution boundary</h2><p>This showcase includes a local browser simulation, not a live commerce service. No backend is connected. Reviewed verification artifacts and evidence viewing are reserved for C3.</p></section>
      </main>
      <footer><a href="https://github.com/nhatminh06/commercecore">Source repository</a><span>Local foundation · No live system status</span></footer>
    </div>
  </>;
}
