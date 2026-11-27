import { PageHeader } from '../components/PageHeader';

export function Overview() {
  return <>
    <PageHeader title="Overview" description="A static introduction to CommerceCore and its correctness boundaries." />
    <section className="panel" aria-labelledby="page-status">
      <p className="eyebrow" id="page-status">Foundation ready</p>
      <p>CommerceCore combines a modular Spring Boot application, local PostgreSQL transactions, a separate gRPC Payment Service, and asynchronous Kafka processing. This showcase foundation is independent of that implementation.</p>
      <ul><li><a href="https://github.com/nhatminh06/commercecore/blob/c17118e3db9250cd2d51476ecce05bbee97f4f42/README.md">Project documentation</a></li></ul>
    </section>
  </>;
}
