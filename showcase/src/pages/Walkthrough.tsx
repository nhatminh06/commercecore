import { PageHeader } from '../components/PageHeader';

export function Walkthrough() {
  return <>
    <PageHeader title="Simulated checkout" description="A planned browser-only explanation of checkout, retries, and payment ambiguity." />
    <section className="panel" aria-labelledby="page-status">
      <p className="eyebrow" id="page-status">Not implemented</p>
      <p>The future walkthrough will be explicitly simulated. It will not submit an order, authorize a payment, or exercise the real distributed system.</p>
      <ul><li><a href="https://github.com/nhatminh06/commercecore/blob/c17118e3db9250cd2d51476ecce05bbee97f4f42/docs/checkout.md">Real checkout design</a></li></ul>
    </section>
  </>;
}
