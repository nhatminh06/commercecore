import raw from '../../evidence/catalog.json' with { type: 'json' };

export type Evidence = {
  id: 'inventory' | 'checkout' | 'synthetic'; title: string;
  sourceKind: 'captured_test_execution' | 'synthetic_mutation';
  scenario: string; property: string; verdict: 'PASS' | 'FAIL' | 'INCONCLUSIVE';
  operations: number; schemaVersion: number; runId: string; captureDate: string;
  captureRevision: string | null; captureRevisionStatus: string; toolRevision: string;
  provenance: string; qualification: string; historySha256: string; checkerSha256: string;
  sourceFiles: string[]; sourceHashes: Record<string, string>; bundleHashes: Record<string, string>;
  assumptions: string[]; recordedResult: { verdict: string; checked_operations: number; reason: string; witness: string[] | null };
  report: string; reportSha256: string; generatedHtmlSha256: string; assetHashes: Record<string, string>;
  changes: string; limitation: string; reproduce: string; ownerDeploymentApproval: string;
};
export const sourceLabel = (kind: Evidence['sourceKind']) => kind === 'captured_test_execution' ? 'Locally observed bounded test execution' : 'Synthetic mutation — not an observed application defect';
export const verdictLabel = (entry: Evidence) => `${entry.verdict} · ${entry.sourceKind === 'synthetic_mutation' ? 'checker model counterexample' : 'conditional on declared scope'}`;
const digest = /^[a-f0-9]{64}$/;
export function validateCatalog(value: unknown): asserts value is Evidence[] {
  if (!Array.isArray(value) || value.length !== 3) throw new Error('Expected exactly three reviewed candidates');
  const seen = new Set<string>();
  for (const item of value) {
    if (!item || typeof item !== 'object') throw new Error('Malformed evidence');
    const e = item as Evidence;
    if (!['inventory', 'checkout', 'synthetic'].includes(e.id) || seen.has(e.id)) throw new Error('Unsafe evidence identity');
    seen.add(e.id);
    const kind = e.id === 'synthetic' ? 'synthetic_mutation' : 'captured_test_execution';
    if (e.sourceKind !== kind || !['PASS', 'FAIL', 'INCONCLUSIVE'].includes(e.verdict)) throw new Error('Source kind or verdict mismatch');
    if (!Number.isInteger(e.operations) || e.operations < 1 || e.operations > 32 || e.schemaVersion !== 1) throw new Error('Invalid history shape');
    for (const key of ['title','scenario','property','runId','captureDate','captureRevisionStatus','provenance','qualification','changes','limitation','reproduce'] as const) {
      if (typeof e[key] !== 'string' || !e[key].trim()) throw new Error('Missing provenance: ' + key);
    }
    if (!/^[a-f0-9]{40}$/.test(e.toolRevision) || e.captureRevision !== null || !e.captureRevisionStatus.startsWith('UNVERIFIED')) throw new Error('Capture revision must remain explicitly unverified');
    if (!Array.isArray(e.assumptions) || e.assumptions.join('|') !== 'Exclusive target scope|No unrecorded writers|Logical identities match actual calls') throw new Error('Missing scope assumptions');
    if (!e.recordedResult || e.recordedResult.verdict !== e.verdict || e.recordedResult.checked_operations !== e.operations) throw new Error('Result mismatch');
    if (e.report !== `/reports/${e.id}` || e.ownerDeploymentApproval !== 'PENDING') throw new Error('Unsafe publication target');
    for (const hash of [e.historySha256, e.checkerSha256, e.reportSha256, e.generatedHtmlSha256]) if (!digest.test(hash)) throw new Error('Invalid hash');
    const expected = [`reports/${e.id}.html`, `reports/${e.id}.js`, 'reports/debugger.css', 'reports/favicon.svg'].sort();
    if (!e.assetHashes || Object.keys(e.assetHashes).sort().join('|') !== expected.join('|')) throw new Error('Unsafe artifact allowlist');
    for (const hash of Object.values(e.assetHashes)) if (!digest.test(hash)) throw new Error('Invalid asset hash');
    const files = e.id === 'synthetic' ? ['history.json','manifest.json','reduced_history.json','reduction.json'] : ['history.json','manifest.json'];
    if (!e.bundleHashes || Object.keys(e.bundleHashes).sort().join('|') !== files.sort().join('|')) throw new Error('Unsafe bundle allowlist');
    for (const hash of Object.values(e.bundleHashes)) if (!digest.test(hash)) throw new Error('Invalid bundle hash');
    if (!Array.isArray(e.sourceFiles) || !e.sourceFiles.length || e.sourceFiles.some(path => !/^build\/consistency-lab\/(m18|m22-observed|m22-synthetic)\/[a-z_]+\.json$/.test(path))) throw new Error('Unsafe source path');
  }
}
validateCatalog(raw);
export const evidence: Evidence[] = raw;
