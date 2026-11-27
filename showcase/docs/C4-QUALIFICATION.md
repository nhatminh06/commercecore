# C4 final local qualification — 2026-11-27

**C4_CONDITIONAL. Local qualification passes. C5 authorization is blocked by unverified Cloudflare account isolation and requires explicit owner authorization.** No deployment, Cloudflare mutation, DNS change, commit, push, PR, merge, tag, stash or branch change was performed.

## Revision and preservation

Branch: `feat/commercecore-showcase-c4`. HEAD, local main and origin/main: `9ed3fe1540a16455baf0292c52e8afdb73aae910`. Live GitHub main matched the baseline; C3 PR #12 is merged. [Hosted CI run 46083310151](https://github.com/nhatminh06/commercecore/actions/runs/46083310151) succeeded at that revision. That workflow covers backend, existing web frontend, offline lab and deployment configuration; it does not run the showcase tests reported here.

Existing `.gitignore` and `CLAUDE.md` dirty diff preserved: SHA-256 `a12d4e8ad4355361b2d7bafe4030fb4fd56c74b73ebf8bb6d97ebf6ed72633a2`. Untracked `skills-lock.json` preserved: `48e40d47fa2636be5b452081c57f8967f8d61b24ce8aed761af62f5acc09d396`. Existing `stash@{0}: On feat/consistency-lab-m16: preserve local changes for M16` preserved. No evidence or application production source changed.

## Changed-file inventory

- `showcase/tests/routes.spec.ts`: all six routes at 390/768/1440 px; refresh, overflow and response-header assertions; tablet overview screenshot.
- `showcase/tests/walkthrough.spec.ts`: add 768 px scenario coverage.
- `showcase/tests/evidence.spec.ts`: add 768 px coverage for all three reports.
- `showcase/docs/C4-QUALIFICATION.md`: this report.
- `showcase/docs/C4-ASSET-SHA256SUMS.txt`: exact 19-file distribution inventory and SHA-256 hashes, paths relative to dist.
- `showcase/docs/C5-PREVIEW-RUNBOOK.md`: gated future preview procedure.

Generated dist, Wrangler logs, screenshots and PDFs remain ignored local artifacts; dependencies were installed only under showcase. The source evidence and report artifact set remains the merged C3 set in `evidence/REVIEW.md`, `catalog.json` and `approved-inputs.json`.

## Security findings

No verified material showcase security defect found; no redactions or historical evidence regeneration performed. Scope included application components/pages/model/catalog/styles, HTML entries, Vite/Wrangler/headers/package config, evidence preparation/integrity scripts, retained inputs, all eight public report files, diagram SVG and every generated distribution file. All three base64 report payloads were decoded and compared as complete JSON objects with fresh M21 validation; the compiled application's embedded catalog was parsed from its JavaScript AST and exactly matched reviewed catalog JSON.

| Location | Severity | Finding and disposition |
|---|---|---|
| `public/_headers:1-5`, copied to `dist/_headers` | Informational | Restrictive CSP (self scripts/styles/images/fonts; no connections, forms, framing or base URI), nosniff, strict-origin referrer and disabled camera/microphone/geolocation. Real Wrangler responses verified. Hosted headers still require C5 verification. |
| `dist/assets/main-fq1HqBYM.js:1` | Informational | Vite modulepreload fallback contains fetch; this is the bundled loader for same-origin modules, not a backend call. Source has no application network client. Chrome request capture found only same-origin static resources. |
| `dist/assets/main-fq1HqBYM.js:8-9` | Informational | React internals contain innerHTML/dangerouslySetInnerHTML support and error-stack construction. Application source does not invoke unsafe HTML sinks; reports use textContent for evidence. No sensitive runtime stack trace is embedded. |
| `src/pages/{Overview,Architecture,Evidence}.tsx`, `src/components/AppShell.tsx` | Informational | Intentional GitHub navigation links. React diagnostic URLs and SVG/MathML namespace strings in compiled code are not automatic third-party requests. No external scripts/fonts/telemetry or API traffic observed. |
| `scripts/check-build.mjs:32`, `scripts/prepare-evidence.py:23` | Informational | Localhost/private-path strings are negative security scan patterns, not exposed endpoints or personal paths. These scripts are not deployed. |
| `evidence/inputs/*`, `public/reports/*.js` decoded payloads | Informational | Test UUIDs, logical labels, timestamps and outcomes support exact correlation; no real customer/payment data, raw checkout key, credentials, private paths/hosts or exception messages found. Exception fields null. IDs are not treated as secrets merely because they are UUIDs. |
| `public/reports/synthetic.js`, corresponding HTML | Low presentation limitation | Tiny constructed nanosecond offsets round timeline labels to 0 ms; inspector retains exact values, visible text explicitly says constructed intervals. No observed JVM timing claim. Approved artifacts left unchanged. |
| Main application HTML / React root | Informational limitation | JavaScript required for application content and simulation; no offline/no-JavaScript accessibility claim. Not a replacement for server-rendered content. |

Whole-output scans found no recognized credential signatures, machine paths, private endpoints, source maps, unexpected file types or debugging artifacts. No eval/new Function, external script inclusion, backend handler, API binding or real backend connectivity in authored application/report code. Regex scanning is not a universal secret detector. The 19-file explicit inventory limits publication to expected static content; source maps and local evidence working directories are absent.

## Evidence integrity and interpretation

All catalog bundle/report/asset hashes, checker hash and approved-input hashes recalculated successfully. Approved checkout input name `checkout_retry.json` maps to retained `inputs/checkout/history.json`; bytes match. Published dist report hashes match source assets exactly. Reproduction commands in REVIEW use retained bundles and current replay code; no ignored backend artifact is required. Fresh full M21 evidence-object validation verifies reduced-history hashes, metadata, retained operations and checker result, without writing reports.

| Example | Fresh M20 result | Integrity/provenance qualification |
|---|---|---|
| Observed inventory | PASS, 4 operations, 4 explored states | History SHA-256 `0b5afc67cb5721adfe4850db202dbe1e46de04c5a9b0927a81f27e85a965e217`. Locally observed bounded execution; scoped witness under exclusive-target/no-unrecorded-writer/matching-logical-identity assumptions. |
| Observed checkout | PASS, 6 operations, 6 explored states | History SHA-256 `5347f25d70d13327cb8c8f7cd774aa2dea1c1b963d998538bd534db60ca4ceb0`. Same declared scope; repeated returned identity does not establish persisted-row uniqueness. |
| Synthetic oversell | Exhaustive FAIL, 4 operations, 3 explored states | History SHA-256 `d35da6e54fe32adab2b1aef5dadbde439f8a1bb74a98b6123020c90c6484f8a4`. Fresh M21 validation confirms analytical reduction from 4 to 2, both FAIL; reduced SHA-256 `2e11252af18d495d8cba8c3b20697257be7cae8fb13ecf7fca9988061f11d61f`. Constructed fixture, not an observed CommerceCore defect. |

Observed capture date remains November 24, 2026 as reported by artifacts. Original run IDs and timestamps unchanged; historical capture Git revisions remain **UNVERIFIED**. Separately recorded historical checker/recheck revision `8c7a59a0c025ab7121780e4df5b742fc9d7838cf` remains distinct from capture. These C4 rechecks ran baseline tooling at `9ed3fe1540a16455baf0292c52e8afdb73aae910`; that does not establish historical producer provenance. Synthetic clock/topology/scope are visibly qualified inherited fixture metadata, including in inspector and explanation. Hash integrity does not authenticate a producer. No global linearizability, exactly-once Kafka transport, production correctness/readiness, or observed synthetic application-defect claim found. Historical 100-buyers result is linked documentation, not rerun or browser output.

## Simulation accuracy

Compared `src/simulation/model.ts` and walkthrough labels with Java checkout/payment/workflow/reconciliation ownership behavior and `docs/reconciliation.md`. Atomic all-lines checkout, same-cart/key replay after cart edits, conflicting cart rejection, reservation ownership/release once, AUTHORIZED and FAILED/decline, lost-response UNKNOWN, lookup without reauthorization, separate workflow application, expiry-driven REQUIRES_REVIEW and complete reset are represented correctly within the declared educational model. Unit assertions cover those transitions and malformed input boundaries.

It is deterministic, sequential browser memory: fixed products/carts/provider lookup result, manual expiry/workflow actions, 1–64 character demo keys versus backend 255, 20-order bound. No actual SQL locks, concurrent workload, Kafka transport, real authorization, provider/database persistence or automated scheduler is simulated. UI clearly labels educational/browser simulation; reset/reload/leave clears memory. Java backend unchanged.

## Commands and results

Environment: Node `v22.23.4`, npm `11.6.0`, project Wrangler `4.169.0`, installed Google Chrome through Playwright. Working directory showcase unless noted.

| Command/check | Result |
|---|---|
| `npm ci` | 288 packages installed; audit of 289 packages, 0 vulnerabilities. Existing deprecation notices for ESLint 9 and node-domexception; no dependency changes. |
| `npm run lint` | PASS, repeated after test edits. |
| `npm run build` | PASS twice: integrity gate + `tsc --noEmit` + Vite production build. |
| `npm test` | Final: 16 unit PASS, 35 browser PASS, 0 skipped; includes static build/security check. Earlier expanded run had 23 browser PASS before all-route viewport matrix was added. |
| `node --experimental-strip-types scripts/check-evidence.ts` (build gate) | 3 candidates, 8 allowlisted report files verified. |
| `node scripts/check-build.mjs` (test gate) | 7 HTML entries, 19 files, 338,886 bytes; expected types, limits, diagram equality, report hashes and security signatures checked. |
| `npm audit --json` | 0 vulnerabilities at all severities; 288 dependency nodes reported. |
| Root: `PYTHONDONTWRITEBYTECODE=1 python3 tools/consistency_lab/replay.py recheck showcase/evidence/inputs/{inventory,checkout,synthetic}` (each invoked separately) | PASS/4, PASS/6, FAIL/4 as above. |
| Root: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/consistency_lab -p 'test_*.py'` | 43 discovered, 37 passed, **6 PostgreSQL integration tests skipped** (LAB_POSTGRES_TEST not enabled). |
| In-memory Python M21 `debugger.evidence(bundle)` comparison against each decoded report payload; approved-input SHA-256 assertions | PASS all three, including analytical reduction. |
| Acorn AST extraction of embedded compiled catalog, JSON equality with `catalog.json` | PASS. |
| Sorted SHA-256 manifests after independent Vite builds | Exact equality, all 19 files; no nondeterministic asset output found. |
| `WRANGLER_SEND_METRICS=false ... wrangler whoami` | Not authenticated; no login attempted. CLI preview/create/delete help inspected only. |
| Root: `git diff --check` | PASS. |

Two builds used identical application/config/lockfile/evidence inputs and npm installation; Vite rebuilds dist rather than reusing compiled outputs. Test-only coverage changed between build invocations and cannot enter dist. Aggregate sorted asset-manifest SHA-256: `3f697e0ab6803e22b848ffb4a6036d4c1fd4d80ee9064db981226932bfa6b3a4`. Full artifact hashes: [C4-ASSET-SHA256SUMS.txt](C4-ASSET-SHA256SUMS.txt). Largest asset `assets/main-fq1HqBYM.js`: 260,357 bytes, SHA-256 `8b2fefd69ef99437335d3fa932ff75bf93c9bbc4b44e98d52df07bd79fd6e588`. Total JS including reports: 306,140 bytes; total CSS: 11,439 bytes; total distribution: 338,886 bytes. Main CSS: 6,088 bytes. No backend build or PostgreSQL/gRPC fault experiment was run for C4.

## Browser/accessibility observations

Local assets served by Wrangler (not Vite dev). Six routes direct/refresh at 390/768/1440 px: PASS, no document horizontal overflow, static-only same-origin requests. Genuine HTTP 404 verified for unknown route, nested route, missing JS and API-looking path. All three reports at all widths: PASS inspector selection, keyboard/focus, timeline scrolling/zoom, witness selection or synthetic original/reduced controls, refresh and CSP. Simulation checks pass at all three widths. Skip link focuses main; form/control keyboard operation and visible outlines pass; reduced-motion transition duration is 0s. Headers verified on route responses; report CSP checked independently. No browser page errors or report console errors.

Visually inspected overview at 768, walkthrough at 390 and synthetic report at 768; content/navigation legible with no page overflow. Visually inspected **all seven rasterized A4 print pages**: inventory 2, checkout 2, synthetic 3. Text and provenance readable; synthetic comparison breaks across pages but retains content; timeline labels truncate in bars with complete inspector text available. Ignored artifacts: `test-results/overview-768.png`, `walkthrough-390.png`, `report-synthetic-768.png`, and `report-{inventory,checkout,synthetic}.pdf` plus rendered page PNGs. No formal WCAG conformance claim, screen-reader certification, Safari/Firefox or physical-device testing.

## Cloudflare compatibility and remaining gates

Current official documentation checked 2026-11-27: [Static Assets](https://developers.cloudflare.com/workers/static-assets/), [asset billing](https://developers.cloudflare.com/workers/static-assets/billing-and-limitations/), [Free limits](https://developers.cloudflare.com/workers/platform/limits/), [Workers Builds](https://developers.cloudflare.com/workers/ci-cd/builds/limits-and-pricing/), [Previews](https://developers.cloudflare.com/workers/previews/), [Preview setup](https://developers.cloudflare.com/workers/previews/get-started/), [Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/), [rollbacks](https://developers.cloudflare.com/workers/versions-and-deployments/rollbacks/). Installed command help confirms `wrangler preview --name` and `wrangler preview delete --name`.

Current configuration is assets-only, no Worker handler, storage, bindings, secrets, routes or paid feature. Nineteen tiny assets are below Free file/size limits; static requests are free/unlimited, separate from script execution quotas. Worker count, shared account/build usage, portfolio/CogniFlow identity/routes/wildcards/domains and target name availability are **UNVERIFIED**, because existing Wrangler session is unauthenticated. No account state inferred from local config. No login, temporary-account fallback or remote mutation attempted.

Current config deliberately lacks a previews block. Runbook supplies a dedicated future config with empty previews block and explicit ignore-base-config flag, to review before C5; no preview config was created or executed here. C5 must verify account isolation, review exact config/revision/hashes, obtain explicit owner authorization, then verify hosted behavior and unchanged existing sites. C6 requires separate approval after C5. **C5 readiness: local package qualified; authorization/execution NOT READY.**

## Final Git status

```text
 M .gitignore
 M CLAUDE.md
 M showcase/tests/evidence.spec.ts
 M showcase/tests/routes.spec.ts
 M showcase/tests/walkthrough.spec.ts
?? showcase/docs/
?? skills-lock.json
```

Nothing staged. Protected unrelated hashes and M16 stash still match preflight. Stop at C4; no automatic C5 progression.
