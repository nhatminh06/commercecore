import { useState } from 'react';
import { PageHeader } from '../components/PageHeader';
import { evidence } from '../evidence/catalog';
import { EvidenceSummary } from '../evidence/EvidenceSummary';

export function Debugger() {
  const [selected, setSelected] = useState('inventory');
  const entry = evidence.find(e => e.id === selected)!;
  return <>
    <PageHeader title="Execution-history debugger" description="Choose a historical observed execution or a synthetic mutation, then open its standalone M21 report." />
    <p className="simulation-label">Owner deployment approval pending. These static reports contain reviewed local candidates; capture Git revisions are UNVERIFIED.</p>
    <label htmlFor="evidence-selection">Evidence example</label><select id="evidence-selection" value={selected} onChange={e => setSelected(e.target.value)}>{evidence.map(e => <option value={e.id} key={e.id}>{e.title}</option>)}</select>
    <EvidenceSummary entry={entry} />
    <section className="panel"><h2>Reading the report</h2><p>Tab to operation buttons and press Enter or Space to inspect plan and observation fields. Zoom changes interval placement; the timeline scrolls horizontally. Synthetic reports include Original and Reduced analytical views. Print using the browser print command.</p><p>A witness is a possible ordering, not an observed execution order. The reduced synthetic history was not executed. No provider, database transaction, Kafka, or network-fault timeline was captured by M18.</p></section>
    <p><a href="/evidence">Read complete provenance, hashes, commands, and limitations</a></p>
  </>;
}
