import assert from 'node:assert/strict';
import { readFile, readdir, stat } from 'node:fs/promises';

const config = JSON.parse(await readFile(new URL('../wrangler.jsonc', import.meta.url), 'utf8'));
assert.deepEqual(Object.keys(config).sort(), ['$schema', 'assets', 'compatibility_date', 'name']);
assert.equal(config.name, 'commercecore-showcase');
assert.deepEqual(config.assets, { directory: './dist', html_handling: 'drop-trailing-slash', not_found_handling: '404-page' });
const root = new URL('../dist/', import.meta.url);
const routes = ['index.html', 'walkthrough/index.html', 'architecture/index.html', 'lab/index.html', 'lab/debugger/index.html', 'evidence/index.html', '404.html'];
for (const route of routes) {
  const html = await readFile(new URL(route, root), 'utf8');
  assert.match(html, /<title>.+ \| CommerceCore<\/title>/);
  assert.match(html, /src="\/assets\//);
  assert.doesNotMatch(html, /(?:src|href)="https?:/);
}
let count = 0;
let bytes = 0;
async function inspect(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const url = new URL(entry.name + (entry.isDirectory() ? '/' : ''), directory);
    if (entry.isDirectory()) { await inspect(url); continue; }
    assert(entry.isFile(), 'Only regular assets are permitted');
    assert(/\.(?:html|js|css)$/.test(entry.name) || entry.name === '_headers', `Unexpected asset: ${entry.name}`);
    const size = (await stat(url)).size;
    assert(size <= 25 * 1024 * 1024);
    const text = await readFile(url, 'utf8');
    assert.doesNotMatch(text, /localhost:8080|localhost:8091|\/api\/commercecore|\/api\/payment-provider|postgres(?:ql)?:\/\/|kafka:\/\//i);
    assert.doesNotMatch(text, /-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|(?:password|api[_-]?key|secret)\s*[:=]\s*["'][^"']{8,}["']/i);
    count++; bytes += size;
  }
}
await inspect(root);
assert(count <= 20000);
console.log(`Static output checked: ${routes.length} HTML entries; ${count} files; ${bytes} bytes. No forbidden endpoints, unexpected file types, or recognized credential patterns.`);
