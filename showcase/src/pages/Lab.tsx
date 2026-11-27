import { PageHeader } from '../components/PageHeader';

export function Lab() {
  return <>
    <PageHeader title="Consistency Lab" description="Bounded tests and independent analysis support scoped claims about CommerceCore." />
    <section className="panel" aria-labelledby="page-status">
      <p className="eyebrow" id="page-status">Evidence catalog not implemented</p>
      <p>Observed service-call histories, synthetic checker mutations, and local qualification are different evidence classes. No generated bundles are published in this scaffold.</p>
      <ul><li><a href="https://github.com/nhatminh06/commercecore/blob/c17118e3db9250cd2d51476ecce05bbee97f4f42/docs/roadmap/CONSISTENCY-LAB-ROADMAP.md">Consistency Lab roadmap</a></li></ul>
    <p className="help">Reviewed verification artifacts will be integrated in C3. The browser simulation is not independent backend evidence.</p>
    </section>
    <p className="project-links"><a href="/lab">Lab scope</a><a href="/lab/debugger">Debugger entry</a><a href="/evidence">Publication boundaries</a><a href="/walkthrough">Local simulation</a></p>
  </>;
}
