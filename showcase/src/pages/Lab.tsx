import { PageHeader } from '../components/PageHeader';
import { evidence } from '../evidence/catalog';
import { EvidenceSummary } from '../evidence/EvidenceSummary';

export function Lab() {
  return <>
    <PageHeader title="Consistency Lab" description="Historical bounded executions and synthetic checker cases, independently rechecked offline for local review." />
    <p className="simulation-label">Local review candidates · owner deployment approval pending. Capture revisions remain UNVERIFIED. No live experiments run on this site.</p>
    <ol className="evidence-pipeline" aria-label="Evidence pipeline"><li><strong>M18 · Record</strong><span>Test-JVM calls against disposable PostgreSQL</span></li><li><strong>M19 · Check</strong><span>Bounded models and possible sequential witness</span></li><li><strong>M20 · Recheck</strong><span>Hashed bundle; analytical FAIL reduction</span></li><li><strong>M21 · Inspect</strong><span>Offline timeline and operation inspector</span></li></ol>
    <p>A PASS admits one sequential explanation under externally declared exclusive scope. It does not prove persisted state, global linearizability, exactly-once transport, or production correctness. Synthetic FAIL is a model counterexample, not an observed application defect.</p>
    {evidence.map(entry => <EvidenceSummary key={entry.id} entry={entry} />)}
    <p className="project-links"><a href="/lab/debugger">Choose a debugger report</a><a href="/evidence">Hashes and reproduction</a><a href="/walkthrough">Separate browser simulation</a></p>
  </>;
}
