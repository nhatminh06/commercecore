import { PageHeader } from '../components/PageHeader';

export function Architecture() {
  return <>
    <PageHeader title="System architecture" description="Local transactions, remote payment outcomes, and asynchronous delivery have separate guarantees." />
    <section className="panel" aria-labelledby="page-status">
      <p className="eyebrow" id="page-status">Presentation pending</p>
      <p>The existing architecture documentation describes ownership and correctness boundaries. Diagram presentation will be added in a later milestone.</p>
      <ul><li><a href="https://github.com/nhatminh06/commercecore/blob/c17118e3db9250cd2d51476ecce05bbee97f4f42/docs/architecture.md">Architecture documentation</a></li></ul>
    </section>
  </>;
}
