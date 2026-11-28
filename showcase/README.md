# CommerceCore technical showcase

This is an educational local browser simulation, not a live commerce service. It is independent of the Java backend, Payment Service, PostgreSQL, Kafka, and Next.js Control Room. It does not execute real checkout or run backend experiments. It presents reviewed recorded histories and a separately labeled synthetic checker counterexample.

## Technology and scope

React and TypeScript, Vite multi-page build, plain CSS, and ordinary document links. React and React DOM are the only runtime dependencies. No router, component library, remote fonts, storage, or API client is included. The palette, typography stacks, compact navigation, and panels follow `web/` without importing its code or changing it. Optional ui-ux-pro-max guidance was not found locally.

Use Node 22.12+ (qualified with 22.23.4) and npm (qualified with 11.6.0). Dependencies are pinned in package.json and package-lock.json; retain both. No repository-level setup is needed.

```sh
cd showcase
npm ci
npm run dev
npm run typecheck
npm run lint
npm run build
npm run test:unit
npm test
npm audit
npm run preview
```

`npm run dev` serves Vite on loopback for editing. `npm run preview` serves the built output through local Wrangler on port 4173, including Cloudflare routing and headers. No authentication or deployment is needed. Build before testing/previewing. Playwright tests use installed Google Chrome (`channel: chrome`); if absent, install Chrome through your normal approved process or report browser tests as unverified. Screenshots are written under ignored `test-results/`.

## Routes and real 404s

Vite receives seven HTML inputs: `/`, `/walkthrough`, `/architecture`, `/lab`, `/lab/debugger`, `/evidence`, and `404.html`. Each output HTML loads the same bundled React shell; pathname selects the page. No client-side router or history interception is used. Route titles are present in HTML; body content requires JavaScript. Native links perform normal document navigation.

Wrangler uses `html_handling: drop-trailing-slash` and `not_found_handling: 404-page`. The six routes have actual `index.html` files; direct loads and reloads work. Unknown paths return the root `404.html` with HTTP 404, not a 200 SPA fallback. Trailing-slash requests are canonicalized by Cloudflare. Use Wrangler preview, rather than assuming Vite preview reproduces Cloudflare semantics.

`npm test` checks output/configuration boundaries, then starts local Wrangler and tests all routes, reloads, navigation, unknown paths, missing assets, headers, browser errors, network requests, focus, and desktop/mobile overflow. These tests are qualification of this scaffold, not CommerceCore correctness tests.

## Hosting architecture and safety

Browser → dedicated `commercecore-showcase` Workers Static Assets → `dist/` only. There is no authored Worker execution handler, service binding, database, or environment secret. The separately authorized production Worker serves https://commercecore.minhpham06.com/ through a Custom Domain, with no wildcard Worker routes. See [production release and maintenance](docs/C6-PRODUCTION-RELEASE.md) for the verified release, account isolation, and approval gates.

The `_headers` Content Security Policy blocks connections (`connect-src 'none'`) and external assets. Documentation links are user-initiated navigations to revision-pinned GitHub source; they are not automatic API requests. Build checks reject unexpected asset types, known backend endpoints and recognized credential patterns. Pattern checks do not establish exhaustive secret detection: review every future public asset. No `.env` or generated lab bundles are included.

Static hosting references checked during C1:
- https://developers.cloudflare.com/workers/static-assets/routing/static-site-generation/
- https://developers.cloudflare.com/workers/static-assets/get-started/

## Boundaries and next milestones

C2 adds a deterministic local walkthrough, source-backed overview and architecture, and explicit C3 entry points. The architecture SVG is an exact copy of docs/diagrams/architecture.current.svg, not runtime evidence. Existing backend, frontend, infrastructure, dirty files, and stashes remain unchanged.

C3 recommendation: review specific existing evidence for privacy and provenance, preserve original hashes/revision/assumptions, recheck it offline, then add a static evidence presentation and debugger. Synthetic counterexamples must remain distinct from observed executions. Neither milestone authorizes infrastructure changes.


## Local simulation model

`src/simulation/model.ts` is a pure immutable transition function. React dispatches actions and renders the result. Three fixed SKUs have integer-cent prices and initial stock 5/3/8; two fixed carts support key-conflict demonstrations. Quantities are integers 0–20 (zero removes a line). Demo keys use 1–64 letters, digits, underscores, or hyphens, a deliberately smaller input contract than the backend's 255-character key limit. At most 20 orders exist per session; identifiers start at SIM-ORDER-1. No random IDs, timestamps, scheduling, network delays, persistence, or browser storage are involved.

| Action | Local model transition |
| --- | --- |
| Checkout | Validate all lines, then decrement available stock and create PENDING/ACTIVE order plus key mapping together |
| Rejected checkout | No inventory, order, reservation, or key mapping change |
| Same key + cart | Return original order snapshot, including after cart edits; no second decrement |
| Same key + other cart | Reject without domain changes |
| Authorization succeeds | Payment AUTHORIZED; order stays PENDING until workflow action |
| Authorization declined | Payment FAILED; workflow later cancels order and releases stock once |
| Response lost | Fixed provider outcome AUTHORIZED, local UNKNOWN; no reauthorization |
| Explicit lookup | UNKNOWN → AUTHORIZED; one lookup, no extra authorization; order still awaits workflow |
| Explicit expiry | UNKNOWN/ACTIVE → EXPIRED and return stock; later lookup requires review |
| Workflow | AUTHORIZED + ACTIVE → CONFIRMED; lost ownership → REQUIRES_REVIEW without confirming |
| Reset | Restore initial inventory, both empty carts, no orders/mappings, initial key and scenario |

These are educational transitions, not PostgreSQL concurrency control, independent correctness evidence, real Kafka delivery, or real provider calls. Authorization and Kafka workflow are intentionally separate user actions. This bounded walkthrough only models one fixed lookup outcome (AUTHORIZED), not every provider outcome or reconciliation race. It does not model automatic reconciliation, refunds, shipping, automatic re-reservation, or an execution timeline. Refreshing or leaving the route also resets memory.

`npm run test:unit` uses Node's built-in test runner and experimental TypeScript stripping (Node 22.12+); no test dependency was added. `npm test` includes those unit tests, output safety checks, all C1 browser checks, and C2 interactions at 390 and 1440 px. Unit tests cover atomicity, identity, payments, review, reset, invalid actions and bounded identifiers. Browser screenshots are under ignored `test-results/overview-*.png` and `test-results/walkthrough-*.png`. The build checker allows the named architecture SVG only when its bytes match the repository source; it does not allow generated evidence bundles.

## C3 — locally reviewed evidence candidates

The `/lab`, `/lab/debugger`, and `/evidence` routes now distinguish historical locally observed bounded tests, synthetic checker mutations, and the separate browser simulation. Three standalone M21 reports are served from local static assets. No C2 simulation rules changed.

See [evidence/REVIEW.md](evidence/REVIEW.md) for the exact privacy review, provenance limitations, preparation command, allowlist, derivative changes and owner-review gate. [evidence/catalog.json](evidence/catalog.json) records all source/derivative hashes. Historical capture Git revisions remain UNVERIFIED by explicit owner instruction; tool/recheck revision is recorded separately. The reviewed artifact text retains its historical pending-approval wording; later owner approval and production qualification are recorded in [the production release record](docs/C6-PRODUCTION-RELEASE.md).

`npm run build` now verifies the retained source bundles, exact checker hash and public asset hashes before TypeScript/Vite. It needs no ignored build artifacts, Python, Docker, Java, PostgreSQL or backend access. The retained inputs and prepared public derivatives are source files intended for inclusion in the eventual reviewed commit; no commit is made by this task. `npm run test:unit` includes manifest, source-kind, assumptions, malformed provenance, allowlist and hash checks. `npm test` adds standalone report selection/inspector/witness/reduction/zoom/scroll/print tests at 390 and 1440 px plus all C1/C2 regressions. Screenshots and print PDFs go only to ignored `test-results/`.

The ordinary site CSP is unchanged. Report scripts/styles were externalized rather than enabling inline code or weakening the global policy. No iframe is used. The M21 implementation remains unchanged. These reports provide model-scoped analysis, not producer authentication, persisted-state proof, global linearizability, exactly-once transport, or production readiness.
