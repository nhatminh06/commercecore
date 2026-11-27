import { PageHeader } from '../components/PageHeader';

export function Debugger() {
  return <>
    <PageHeader title="Execution-history debugger" description="A planned static presentation of reviewed and verified execution-history reports." />
    <section className="panel" aria-labelledby="page-status">
      <p className="eyebrow" id="page-status">Viewer not implemented</p>
      <p>The existing offline debugger analyzes supported M18–M20 histories. No history, witness ordering, or counterexample is loaded here.</p>
      <ul><li><a href="https://github.com/nhatminh06/commercecore/blob/c17118e3db9250cd2d51476ecce05bbee97f4f42/tools/consistency_lab/M21-EXECUTION-DEBUGGER.md">Offline debugger documentation</a></li></ul>
    </section>
  </>;
}
