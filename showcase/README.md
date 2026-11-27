# CommerceCore showcase — C1

This is a frontend scaffold, not a live commerce service. It is independent of the Java backend, Payment Service, PostgreSQL, Kafka, and Next.js Control Room. It does not execute checkout, run experiments, display histories, or publish generated evidence.

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

Browser → dedicated `commercecore-showcase` Workers Static Assets → `dist/` only. There is no Worker execution handler, service binding, database, environment secret, custom domain, or configured route. Cloudflare account isolation, existing resources, Free-plan usage, and domain availability remain UNVERIFIED. Do not deploy until separately approved and checked.

The `_headers` Content Security Policy blocks connections (`connect-src 'none'`) and external assets. Documentation links are user-initiated navigations to revision-pinned GitHub source; they are not automatic API requests. Build checks reject unexpected asset types, known backend endpoints and recognized credential patterns. Pattern checks do not establish exhaustive secret detection: review every future public asset. No `.env` or generated lab bundles are included.

Current documentation checked for C1:
- https://developers.cloudflare.com/workers/static-assets/routing/static-site-generation/
- https://developers.cloudflare.com/workers/static-assets/get-started/

## Boundaries and next milestones

C1 contains page shells, accurate descriptions, explicit pending notices, and source links. It includes no simulated results, catalog, history renderer, or imported evidence. Existing backend, frontend, infrastructure, dirty files, and stashes must remain unchanged.

C2 recommendation: implement the smallest clearly labeled local checkout walkthrough, with deterministic state transitions and tests; retain zero backend requests. C3 recommendation: review specific existing evidence for privacy and provenance, preserve original hashes/revision/assumptions, recheck it offline, then add a static evidence presentation and debugger. Synthetic counterexamples must remain distinct from observed executions. Neither milestone authorizes infrastructure changes.
