import assert from 'node:assert/strict';
import { test } from 'node:test';
import { evidence, validateCatalog, sourceLabel, verdictLabel } from '../src/evidence/catalog.ts';
import { checkEvidence, verifyHash } from '../scripts/check-evidence.ts';

test('reviewed manifest validates; observed and synthetic labels remain distinct', () => {
  validateCatalog(evidence);
  assert.equal(evidence.length, 3);
  assert.match(sourceLabel(evidence[0].sourceKind), /Locally observed/);
  assert.match(sourceLabel(evidence[2].sourceKind), /not an observed application defect/);
  assert.match(verdictLabel(evidence[0]), /PASS.*conditional/);
  assert.match(verdictLabel(evidence[2]), /FAIL.*counterexample/);
  assert(evidence.every(e => e.captureRevision === null));
});
test('reject malformed provenance, missing assumptions and source-kind confusion', () => {
  for (const [key, value] of Object.entries({ provenance: '', toolRevision: 'unknown', captureRevision: '8c7a59a0c025ab7121780e4df5b742fc9d7838cf', sourceKind: 'synthetic_mutation', assumptions: [], historySha256: 'bad', report: '/private/file', recordedResult: null })) {
    const copy = structuredClone(evidence) as unknown as Record<string, unknown>[];
    copy[0][key] = value;
    assert.throws(() => validateCatalog(copy), key);
  }
  assert.throws(() => validateCatalog(null));
  assert.throws(() => validateCatalog([evidence[0]]));
});
test('strict allowlist rejects extra files, traversal and replaced source kind', () => {
  const copy = structuredClone(evidence);
  copy[0].assetHashes['reports/private.json'] = '0'.repeat(64);
  assert.throws(() => validateCatalog(copy), /allowlist/);
  const other = structuredClone(evidence);
  other[2].sourceKind = 'captured_test_execution';
  assert.throws(() => validateCatalog(other), /Source kind/);
  const path = structuredClone(evidence);
  path[0].sourceFiles = ['../../private.json'];
  assert.throws(() => validateCatalog(path), /source path/);
});
test('hash verification fails closed on changed or missing bytes', async () => {
  assert.throws(() => verifyHash(new Uint8Array([1]), '0'.repeat(64)), /hash mismatch/);
  await assert.rejects(() => checkEvidence(new URL('../missing-evidence/', import.meta.url)));
  await checkEvidence();
});

test('synthetic presentation qualifies clock, topology and scope while preserving decoded evidence', async () => {
  const { readFile } = await import('node:fs/promises');
  const { createHash } = await import('node:crypto');
  const expected = {
    inventory: '0b5afc67cb5721adfe4850db202dbe1e46de04c5a9b0927a81f27e85a965e217',
    checkout: '5347f25d70d13327cb8c8f7cd774aa2dea1c1b963d998538bd534db60ca4ceb0',
    synthetic: 'd35da6e54fe32adab2b1aef5dadbde439f8a1bb74a98b6123020c90c6484f8a4',
  };
  for (const entry of evidence) {
    const raw = await readFile(new URL(`../evidence/inputs/${entry.id}/history.json`, import.meta.url));
    assert.equal(createHash('sha256').update(raw).digest('hex'), expected[entry.id]);
    const script = await readFile(new URL(`../public/reports/${entry.id}.js`, import.meta.url), 'utf8');
    const encoded = /atob\("([A-Za-z0-9+/=]+)"\)/.exec(script)![1];
    const decoded = JSON.parse(Buffer.from(encoded, 'base64').toString());
    assert.deepEqual(decoded.history, JSON.parse(raw.toString()));
    assert.deepEqual(decoded.result, entry.recordedResult);
    const html = await readFile(new URL(`../public/reports/${entry.id}.html`, import.meta.url), 'utf8');
    if (entry.id === 'synthetic') {
      assert.match(html, /Horizontal positions represent constructed intervals/);
      assert.match(html, /not measured JVM execution timestamps/);
      assert.doesNotMatch(html, /Horizontal position is the M18 test JVM monotonic clock/);
      assert.match(html, /Constructed interval/);
      assert.match(script, /Inherited fixture metadata; synthetic intervals are constructed, not measured JVM timing/);
      assert.match(script, /Inherited fixture metadata; does not describe an observed execution/);
      assert.match(script, /Inherited checker-contract scope label; does not mean these synthetic calls occurred/);
      assert.equal(decoded.source, 'synthetic_mutation');
      assert.equal(decoded.result.verdict, 'FAIL');
      assert.equal(decoded.reduced.history.plannedCount, 2);
      assert.equal(decoded.reduced.result.verdict, 'FAIL');
      assert.deepEqual(decoded.reduced.history, JSON.parse(await readFile(new URL('../evidence/inputs/synthetic/reduced_history.json', import.meta.url), 'utf8')));
    } else {
      assert.match(html, /Horizontal position is the M18 test JVM monotonic clock/);
      assert.match(html, /Observed execution/);
      assert.equal(decoded.source, 'captured_test_execution');
    }
  }
});
