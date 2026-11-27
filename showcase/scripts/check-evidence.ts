import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { evidence } from '../src/evidence/catalog.ts';

export function verifyHash(bytes: Uint8Array, expected: string) {
  if (createHash('sha256').update(bytes).digest('hex') !== expected) throw new Error('Evidence hash mismatch');
}
export async function checkEvidence(directory = new URL('../public/', import.meta.url)) {
  const allowed: Record<string, string> = {};
  const checker = await readFile(new URL('../../tools/consistency_lab/history_checker.py', import.meta.url));
  for (const entry of evidence) {
    verifyHash(checker, entry.checkerSha256);
    for (const [name, hash] of Object.entries(entry.bundleHashes)) {
      verifyHash(await readFile(new URL(`../evidence/inputs/${entry.id}/${name}`, import.meta.url)), hash);
    }
    for (const [path, hash] of Object.entries(entry.assetHashes)) {
      if (allowed[path] && allowed[path] !== hash) throw new Error('Conflicting report asset');
      allowed[path] = hash;
      const bytes = await readFile(new URL(path, directory));
      verifyHash(bytes, hash);
      if (path.endsWith('.html') && /<script>|<style>/.test(bytes.toString())) throw new Error('Inline report code is forbidden');
    }
    if (entry.reportSha256 !== entry.assetHashes[`reports/${entry.id}.html`]) throw new Error('Report hash mismatch');
  }
  const names = await readdir(new URL('reports/', directory));
  if (names.sort().join('|') !== Object.keys(allowed).map(p => p.slice(8)).sort().join('|')) throw new Error('Unexpected public report file');
  console.log(`Verified ${evidence.length} evidence candidates and ${names.length} allowlisted report assets.`);
}
if (process.argv[1] === fileURLToPath(import.meta.url)) await checkEvidence();
