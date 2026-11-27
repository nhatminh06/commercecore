import { sourceLabel, verdictLabel } from './catalog';
import type { Evidence } from './catalog';

export function EvidenceSummary({ entry }: { entry: Evidence }) {
  return <section className="panel" aria-labelledby={`title-${entry.id}`}>
    <p className="eyebrow">{sourceLabel(entry.sourceKind)}</p>
    <h2 id={`title-${entry.id}`}>{entry.title}</h2>
    <p><strong>{verdictLabel(entry)}</strong> · {entry.operations} operations · {entry.property}</p>
    <p>{entry.limitation}</p>
    <p className="help">{entry.sourceKind === 'synthetic_mutation' ? 'Synthetic fixture date (not capture)' : 'Capture date (artifact-reported)'}: {entry.captureDate}. Capture Git revision: UNVERIFIED. Rechecks were performed separately from capture; no new execution is claimed.</p>
    <p><a href={entry.report}>Open {entry.title.toLowerCase()} report</a></p>
  </section>;
}
