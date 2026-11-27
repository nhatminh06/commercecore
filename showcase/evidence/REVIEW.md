# C3 evidence publication review — local candidates only

Owner approval for public deployment: **PENDING**. Nothing has been deployed. The allowlist is a local preparation gate, not authorization to publish.

## Provenance

`catalog.json` records each exact source path, source-file hashes, original history hash, checker hash, M19 result, M20 bundle file hashes, generated M21 HTML hash and final derivative asset hashes. `approved-inputs.json` pins the seven explicitly reviewed local input files. `inputs/` retains the three complete bundles for source-based offline reproduction; these JSON files are not copied into public assets. The checkout manifest was newly created during C3 using the existing M20 tool and an external exclusive-scope declaration. Inventory and synthetic bundle bytes are unchanged.

The two observed histories report November 24, 2026 and retain their original run IDs and test UUIDs. **Capture Git revision: UNVERIFIED.** Neither M18 nor M20 recorded it. The owner explicitly instructed that it must not be inferred. The source kind is locally observed bounded test execution, with self-reported test-JVM/Spring-service/disposable-PostgreSQL provenance; it is not authenticated producer identity. The separately recorded tool/recheck revision is `8c7a59a0c025ab7121780e4df5b742fc9d7838cf`. The M22 qualification document's `6c164a0` revision is context, not capture provenance.

Fresh C3 offline rechecks are distinct from original historical capture. No new backend workloads were run. Both observed histories admit a PASS witness under exclusive-target, no-unrecorded-writer and matching-logical-identity assumptions. Without that declaration they are INCONCLUSIVE. The stock-1 synthetic mutation is exhaustive FAIL; M21 validation also checks its original/reduced hashes, retained operations, metadata and exhaustive reduced FAIL. Four operations reduce analytically to two. Neither synthetic history was an observed CommerceCore defect.

## Privacy inspection and allowlist

The reviewed histories contain bounded plans, monotonic intervals, artifact-reported timestamps, test-only UUIDs, outcomes and fixed logical identity labels. All exception fields are null. The raw checkout idempotency key and cart/customer records are absent; `shared-checkout-key` is a logical label, not the raw key. Test run/result identities and timestamps are retained because they support correlation and exact-byte rechecks. They are not customer identities. No credential, token, private address/hostname, machine path, customer information, or exception message was found in the selected bundles. Strict M18/M20 schema validation and a privacy-marker scan run before preparation; this does not claim universal secret detection.

The generated HTML's base64 payload is produced only from those validated, reviewed bundles. The decoded evidence receives M21's own schema, hash and reduction validation before rendering. Generated HTML, script payloads, styles and catalog were inspected as derivatives, not assumed safe because they are encoded. The synthetic history's generic topology field does not make it observed: the authoritative source-kind label and banner explicitly identify it as synthetic.

Only these public report assets are allowed:

- `reports/inventory.html`, `reports/inventory.js`
- `reports/checkout.html`, `reports/checkout.js`
- `reports/synthetic.html`, `reports/synthetic.js`
- `reports/debugger.css`, `reports/favicon.svg`

Each file is hash-pinned in `catalog.json`; unexpected report files and hash mismatches fail frontend build checks. No entire build directory is copied. No histories, JSON downloads, filesystem APIs, backend endpoints or remote assets are added to the deployed runtime.

## Derivative changes

The original M21 implementation is unchanged. Preparation externalizes the generated inline style and script into same-origin files, adds an explicit provenance/owner-review banner and a local CC favicon. Synthetic derivative text labels identify intervals and inspector outcomes as modeled rather than observed; control logic is unchanged. The synthetic artifact date is a fixture value, not a capture date. Original evidence bytes and payload semantics are preserved; no redaction occurred. The final HTML hash intentionally differs from the original generated HTML hash. The site-wide CSP remains unchanged: self-only scripts/styles, no network connections, no frames. Reports open as standalone documents, not embedded frames.

## Reproduce locally

From repository root, Python 3 only:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/consistency_lab/replay.py recheck showcase/evidence/inputs/inventory
PYTHONDONTWRITEBYTECODE=1 python3 tools/consistency_lab/replay.py recheck showcase/evidence/inputs/checkout
PYTHONDONTWRITEBYTECODE=1 python3 tools/consistency_lab/replay.py recheck showcase/evidence/inputs/synthetic
```

Regenerate from the exact original local sources, only when those hash-pinned files remain available:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 showcase/scripts/prepare-evidence.py \
  --inventory build/consistency-lab/m22-observed \
  --checkout build/consistency-lab/m18/checkout_retry.json \
  --synthetic build/consistency-lab/m22-synthetic
```

Missing/mismatched/oversized/symlinked inputs, extra bundle files, malformed schemas or inconsistent rechecks abort before outputs are written. Output paths are fixed inside showcase. No capture Git revision is added. Re-running against the same inputs produces identical catalog/report bytes. Normal `npm run build` does not run this preparation command or read ignored `build/` evidence.

For an offline debugger re-render, use the original debugger CLI with one of the retained bundles and a new output file. That produces the original inline self-contained HTML; use preparation for the CSP-compatible web derivative. Actual workload reproduction needs Java 21/Docker and the documented M18 test, produces a new run ID/schedule, and must never be described as reproducing the historical execution.

## Outstanding owner gate and C4 recommendations

Review the exact catalog, retained bundles and public report files before any deployment approval. Capture-revision provenance remains unverified. C4 should qualify the intended Cloudflare account/domain isolation, repeat preview/header/404/print checks on the hosted candidate, and review publication approval against the exact final hashes. No C4 work is authorized by this milestone.


## C3.1 correction and refreshed publication review — 2026-11-27

**C3_PUBLICATION_REVIEW_READY — explicit owner publication approval still PENDING.** No commit, push, deployment or C4 work was performed.

Synthetic-only derivative transformations now state that intervals are constructed, not measured JVM timestamps. Clock and topology retain their exact raw values with inherited-fixture explanations. The displayed checker scope is qualified as a contract label that does not mean these calls occurred in CommerceCore. The React evidence page likewise qualifies synthetic topology. Raw histories, bundles, original manifests, numeric fields, run IDs, dates and capture revision status are unchanged. Observed reports remain byte-identical. The original M21 implementation and control behavior are unchanged.

C3.1 source changes: `scripts/prepare-evidence.py`, `src/pages/Evidence.tsx`, `tests/evidence.test.ts`, `tests/evidence.spec.ts`, `evidence/catalog.json`, `public/reports/synthetic.html`, `public/reports/synthetic.js`, and this review document. Preparation writes only changed bytes, so unchanged artifacts are not rewritten.

Qualification: Node 22.23.4, npm 11.6.0; `npm ci`, `npm run build` (evidence verification + TypeScript + Vite), `npm run lint`, `npm run test:unit` (16 passed), `npm test` (16 unit + 19 Chrome/Workers-local browser tests), `npm audit` (0 vulnerabilities), and `git diff --check` passed. Existing replay CLI rechecks returned inventory PASS (4), checkout PASS (6), synthetic FAIL (4); M21 verified the 4→2 analytical FAIL reduction. Repeated preparation produced identical bytes without rewriting destinations. Invalid input hashes were rejected before outputs changed. An isolated source-only build with existing installed dependencies and no ignored lab files produced a byte-identical distribution. Decoded payloads retain original data and results. Browser coverage includes keyboard, inspector, zoom, horizontal scroll, original/reduced views, refresh, 404, CSP and local-only network behavior at 390/1440 px. Screenshots are in ignored `test-results/report-synthetic-{390,1440}.png`; print output is `test-results/report-synthetic.pdf` (three pages, visually inspected after rasterization).

Final distribution: 19 files, 338886 bytes. Main JavaScript: 260.35 kB / 80.17 kB gzip; CSS: 6.08 kB / 1.88 kB gzip. Privacy review covered source evidence, decoded payloads, final HTML/JS/CSS/SVG and compiled catalog: no credentials, customer information, private network targets or machine paths found. The existing test IDs/timestamps remain intentional provenance data. No backend or external asset requests occurred in browser tests; CSP/header source remains unchanged. No requested local check was skipped; new backend/database/network-fault execution, public hosting checks and C4 were outside scope.

Original history hashes remain:

- Inventory: `0b5afc67cb5721adfe4850db202dbe1e46de04c5a9b0927a81f27e85a965e217`
- Checkout: `5347f25d70d13327cb8c8f7cd774aa2dea1c1b963d998538bd534db60ca4ceb0`
- Synthetic: `d35da6e54fe32adab2b1aef5dadbde439f8a1bb74a98b6123020c90c6484f8a4`
- Synthetic reduced: `2e11252af18d495d8cba8c3b20697257be7cae8fb13ecf7fca9988061f11d61f`

### Changed derivative hashes

The original M21 generated-HTML hashes remain unchanged; these are presentation derivative changes. Inventory/checkout report copies, shared CSS/favicon, architecture SVG and headers are unchanged.

| Old path → new path (same unless shown) | Old SHA-256 | New SHA-256 |
| --- | --- | --- |
| `showcase/dist/404.html` | `49daf492b7108b998e4ff5fda1d439d70068368d83ed9173af742800c1f54b0b` | `6097e83cac100f4e8f20cbaf7297a6eaf7bf0a88c3fb4322f9f1e580fdc9ab19` |
| `showcase/dist/architecture/index.html` | `ab5599a241a51cb1077f861590aeb77f84d03f959f8abe8cee18d865701e8abf` | `2a1496ce82ba0ab79aa841967e85bc3bf1af1f5dddd16ea888fbdd15b9973caf` |
| `showcase/dist/evidence/index.html` | `004be0b8de5af5d0ea80efebca8780a27b42a32eaa96c455e8be06b65683c6f0` | `33a9bd7ec6061086182ad9262bf6756224b363a458ac62b3f8931b7d609dde2c` |
| `showcase/dist/index.html` | `f29865fe58e95a140ddbcf16705352b6d3426c2e51b019f65f5f364e7aeb3eda` | `98863e11afe250b02814f512ef6e8ec6041e6e9a8b4c5bcdbfb4a8e59bf91cf2` |
| `showcase/dist/lab/debugger/index.html` | `5b758b3d858961bf1921012dffdca1d72a998232ebc1eeb4c9b9fd0d8d90e14a` | `1eec063d38ba0cca90b664696d7844a1b3011aeace45c275f520214b7a0f4f11` |
| `showcase/dist/lab/index.html` | `12b2dccdf251dad140974c24024097489317d0de90e9d8668091dec3a78c5bf3` | `9c89e3fcdfd6c91eb7c4e032d06afe31c25ecd605f0b28298884305357307f45` |
| `showcase/dist/reports/synthetic.html` | `8e463dd3f8c273544638297ece7e6a2a00ee0222ac2ca17647947f5a567a1626` | `6e8dc02caf01d4b6454f45e7d5aeb7135a604140ef49468e93b7c856d1f78e0c` |
| `showcase/dist/reports/synthetic.js` | `c956e8e4081f701297610579e2cc484fec093860b13f6deddc63693ce6be2aa7` | `10fce38d870a93641d753b5814ca46f74c9797e6fd96ca4a6baebde9d6629cc2` |
| `showcase/dist/walkthrough/index.html` | `3d8758969e5f6e1fa17f5d5795bf8cc67a84846d5ade191fdad8d8a932e04174` | `9266622123f3c301f7c37415e85a3bb7d9f31a243c8e8f2c8a5b82bb2b01433f` |
| `showcase/evidence/catalog.json` | `05124ff667916c7facc781515b4864ad5b21affd153a17c697f3520e4c9de3bb` | `8d96dd2a97ff8d2473558ed374369aea7af4471cebf9f2b135fcc757dae7b60d` |
| `showcase/public/reports/synthetic.html` | `8e463dd3f8c273544638297ece7e6a2a00ee0222ac2ca17647947f5a567a1626` | `6e8dc02caf01d4b6454f45e7d5aeb7135a604140ef49468e93b7c856d1f78e0c` |
| `showcase/public/reports/synthetic.js` | `c956e8e4081f701297610579e2cc484fec093860b13f6deddc63693ce6be2aa7` | `10fce38d870a93641d753b5814ca46f74c9797e6fd96ca4a6baebde9d6629cc2` |
| `showcase/dist/assets/main-Ba2P2k3z.js → showcase/dist/assets/main-fq1HqBYM.js` | `8080d43a463447d41873117176785f8051ca4e985f89326ab2319b08a6d032bf` | `8b2fefd69ef99437335d3fa932ff75bf93c9bbc4b44e98d52df07bd79fd6e588` |

### Owner decision

All three examples are ready for owner review of these exact candidate files, including the retained bundles and final derivative hashes in `catalog.json`. Observed capture Git revisions remain UNVERIFIED; this limitation is not repaired or inferred. Synthetic evidence remains a checker-model counterexample, never an observed CommerceCore defect. READY is not approval: the owner must separately approve public repository publication; deployment is also not authorized.

