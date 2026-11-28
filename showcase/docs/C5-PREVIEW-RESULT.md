# C5 isolated preview result

Preview upload completed 2026-11-27 after explicit owner authorization to create `commercecore-showcase` and upload reviewed assets only to `c5-qualification`. No production deployment, DNS, custom-domain or existing-service changes were requested or performed. No commit, push or PR was made.

- Source HEAD: `cfe1fe095cdc109452730a98d1a08e28d5c03da1`, branch `feat/commercecore-showcase-c4`.
- Account: verified Nhat Minh, Workers Free.
- Configuration: `showcase/wrangler.preview.jsonc`, existing assets-only configuration plus empty `previews` block. Remote Previews Base configuration ignored.
- Named preview: https://c5-qualification-commercecore-showcase.mphamnhat2006.workers.dev
- Immutable deployment: https://796bd700-commercecore-showcase.mphamnhat2006.workers.dev
- Preview ID: `7f966bd8cf0742878c701e49c337c61a`.
- Deployment ID: `796bd700-ac18-44e6-b9b3-ac54c10555fe`, number 1, created `2026-11-27T23:57:59.726753Z`.
- Production deployment list for `commercecore-showcase`: empty after upload.

## Validation

During the original C5 qualification, fresh `npm ci`, lint, build (including TypeScript and evidence integrity), 16 unit tests, 35 local Chrome browser tests, npm audit (zero vulnerabilities), all 19 distribution hashes, offline M20 rechecks (inventory PASS/4, checkout PASS/6, synthetic FAIL/4) and git diff whitespace checks passed. Historical evidence bytes and provenance unchanged.

The same 35 browser checks passed against the named hosted preview at 390/768/1440 px. Ignored temporary copies of the existing specs changed only the base URL/network-origin assertions; no server or authored application source changed. Coverage includes six routes and refresh, 404, security headers, keyboard/focus, reduced motion, simulation/reset, all debugger reports, selection/zoom/scroll/reduction, screenshots and print generation. Hosted synthetic tablet screenshot visually inspected.

All **18 public files** match the C4 SHA-256 manifest on **both URLs**, including the browser navigation 404 body. `_headers` is a control file, not downloadable public content. Wrangler reported reading 26 filesystem entries and uploading 18 assets; the local manifest contains 19 files including `_headers`. No unexpected public file was uploaded in the reported asset list. Cloudflare returns a generated `no-op-worker.js` module in deployment metadata for this assets-only upload; there is no authored execution handler or binding.

Preview root responses include noindex, the reviewed CSP, Permissions-Policy, Referrer-Policy and nosniff. Browser request assertions found only same-origin static resources; no API requests or report console errors. Navigation receives the custom HTML 404; raw curl requests without browser navigation metadata receive plain `Not found` with status 404. Initial raw-client attempts to compare that body failed; real browser navigation confirmed the exact reviewed 404 bytes. This is a request-mode difference, not changed evidence.

The approved report text still says deployment approval pending; it was preserved to avoid silently changing approved artifact hashes. This result records the later authorization and preview deployment. It does not authorize production promotion or C6.

## Existing-service isolation

Read-only CLI deployment snapshots before/after agree:

- CogniFlow active version `22909605-0e00-4189-89a8-0406342ce256` (100%).
- Portfolio Pages production deployment `a77eab4c-ca0d-4a91-9a34-f32b3a2a16b6`, source `dd5a850`.

Post-upload public GET: portfolio `https://minhpham06.com/` returns 200; CogniFlow root redirects 307 and resolves to 200 at `/focus`. There was no pre-upload public-response snapshot because the earlier combined smoke/upload command was rejected before execution; do not infer byte-identical before/after public content. Zone DNS and route inventory were inspected during C5.0; not independently re-queried after upload. The upload configuration contains no domains/routes or shared resources, and no DNS/route mutation command was run.

## Cleanup and limits

Preview-only removal, requiring authorization for deletion:

```sh
cd showcase
npx --no-install wrangler preview delete --config wrangler.preview.jsonc --worker-name commercecore-showcase --name c5-qualification
```

This leaves the parent Worker; do not delete it, roll back production or modify existing sites without separate authorization. The current authorization did not request preview deletion, so it remains available for review.

No PostgreSQL/gRPC fault experiments or production qualification were performed. No domain is attached; intended future hostname is `commercecore.minhpham06.com`. Stop after preview verification. C6 requires separate owner approval.

## Read-only closeout review

PR #13 was OPEN with all five reported checks successful at closeout. C4 merge is a prerequisite to opening the proposed C5 follow-up PR; no merge or PR was performed here. The deployed source revision remains `cfe1fe095cdc109452730a98d1a08e28d5c03da1`.

Fresh read-only browser navigation verified all 18 public response hashes and HTML CSP/nosniff/noindex headers on both named and immutable URLs. The 35-test hosted result and no-backend-request assertions were reviewed from retained specs/result and the original execution; the full suite was not rerun for closeout. This is not independent full requalification. Nineteen distribution files include the processed `_headers` control file, accounting for 18 public assets.

Fresh authenticated dashboard inspection confirmed Nhat Minh / Workers Free Active; two Workers plus one Pages project; CommerceCore production has no deployment, custom domain, routes or bindings, and `c5-qualification` remains Ready at displayed version `796bd700` with zero bindings. CogniFlow remains at displayed version `22909605` / 100% with its existing custom domain, assets, two rate limiters and D1 database. Portfolio Pages `myprofile` remains at production deployment `a77eab4c-ca0d-4a91-9a34-f32b3a2a16b6`, with `minhpham06.com` and no production resource bindings.

The complete `minhpham06.com` DNS table still contains exactly four entries: apex proxied CNAME to `myprofile-3r7.pages.dev`, two apex NS records for `launch1.spaceship.net` / `launch2.spaceship.net`, and the managed CogniFlow record. No wildcard DNS record, CommerceCore DNS record or Worker route is present. Subscriptions still list only Workers Free Active and the zone Free Plan Active; no paid subscription appeared. This verifies the inspected application dependencies, not an exhaustive account-wide storage-resource audit.

During closeout the owner explicitly confirmed production hostname `commercecore.minhpham06.com` in zone `minhpham06.com`. The hostname discrepancy is resolved; this confirmation does not authorize production deployment or domain attachment. Neither candidate hostname was attached. C4 was subsequently merged as noted below; C6 remains blocked on release-record completion and separate explicit approvals for production deployment and Custom Domain attachment. No infrastructure was changed during closeout.

## Release-record finalization

C4 PR #13 merged as `966f23604d894568690b05d8d0ed3f3583a00867` on November 27, 2026 at 18:08:50 America/Chicago (2026-11-28T00:08:50Z). The C5 release-record branch is `feat/commercecore-showcase-c5`, based on that merge. This changes neither the historical preview source revision nor its deployment identifier.

Verified usage snapshots during account inspection: before preview creation, 1,538/100,000 daily Worker requests and 0/200,000 daily observability events; a later UTC-day snapshot showed 4/100,000 and 0/200,000. These are point-in-time observations, not current usage guarantees. Workers Free and the zone Free Plan were Active; no paid subscription or CommerceCore storage/service binding appeared.

Finalization reviews the existing hosted qualification rather than rerunning the full suite or deploying again. Only the result, runbook and dedicated preview configuration are proposed for this PR. No production activation is authorized.
