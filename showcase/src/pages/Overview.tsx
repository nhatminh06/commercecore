import { PageHeader } from '../components/PageHeader';

export function Overview() {
  return <>
    <PageHeader title="Overview" description="CommerceCore explores where local transactional guarantees end and remote uncertainty begins." />
    <section className="panel"><h2>One commerce application. One remote payment boundary.</h2><p>A modular Java/Spring Boot application owns carts, orders, inventory reservations, and its PostgreSQL database. A separate gRPC Payment Service owns provider request identity and outcomes in a second PostgreSQL database. That provider is a project implementation, not a real payment processor.</p></section>
    <section className="panel"><h2>Checkout must reserve every line together</h2><p>Checkout snapshots prices, reserves inventory, creates an order, and records outbox intent in one local transaction. If a line cannot be reserved, the transaction rolls back. A persistent idempotency key returns the original order for same-cart retries and rejects reuse for another cart.</p><p className="help">Historical verified local experiment, documented in the repository: 100 buyers competing for 10 units produced 10 winners, 90 rejections, and stock 0. This showcase does not run that experiment or independently reproduce its result.</p><a href="https://github.com/nhatminh06/commercecore/blob/c17118e3db9250cd2d51476ecce05bbee97f4f42/docs/failure-lab.md">Read the recorded local experiment</a></section>
    <section className="panel"><h2>A lost response is uncertainty</h2><p>A gRPC deadline can leave the local payment UNKNOWN while the provider has already authorized. Explicit reconciliation asks the provider what happened. It does not authorize again. Kafka carries committed outbox events at least once; durable consumer receipts deduplicate logical processing, not physical delivery.</p></section>
    <nav className="project-links" aria-label="Explore project"><a href="/walkthrough">Try the local simulation</a><a href="/architecture">Architecture and ownership</a><a href="/lab">Consistency Lab</a><a href="https://github.com/nhatminh06/commercecore">GitHub source</a></nav>
  </>;
}
