# C5 isolated preview runbook

Prepared 2026-11-27 against C4 baseline `9ed3fe1540a16455baf0292c52e8afdb73aae910`, Wrangler 4.169.0. The owner subsequently authorized the isolated C5 preview; its upload and validation are recorded in [C5-PREVIEW-RESULT.md](C5-PREVIEW-RESULT.md). The commands below remain a procedure, not authorization to repeat deployment or delete the preview. Production promotion and Custom Domain attachment remain separately gated.

## Account and isolation gate

Use an already authenticated account or ask the owner to authenticate separately; never put tokens in repository files or logs. `npx --no-install wrangler whoami` must identify the intended account. Do not use a temporary account as a workaround.

In the read-only Cloudflare dashboard, record account identity/Free plan, Workers inventory and remaining capacity, account request/build usage, workers.dev subdomain, and whether `commercecore-showcase` already exists. If present, inspect its owner, deployments, bindings, secrets, routes, custom domains and Previews Base configuration; stop on an unexpected resource or name collision. Inspect portfolio and CogniFlow Worker names, deployment/version IDs, bindings and domains, every relevant zone's Worker routes (including wildcards), and DNS assignments. Do not guess their names or URLs. Record approved public URLs and GET their status/content/headers before and after C5. Keep sensitive account inventories outside public artifacts. Any unknown isolation item blocks authorization.

## Exact configuration and build

Current `showcase/wrangler.jsonc` is assets-only with no handler or bindings. C5 added the following dedicated config at `showcase/wrangler.preview.jsonc` (not part of C4):

```json
{
  "$schema": "./node_modules/wrangler/config-schema.json",
  "name": "commercecore-showcase",
  "compatibility_date": "2026-11-27",
  "assets": {
    "directory": "./dist",
    "html_handling": "drop-trailing-slash",
    "not_found_handling": "404-page"
  },
  "previews": {}
}
```

Review against installed Wrangler schema/help before use. No `main`, routes, custom domains, secrets, environment variables, D1, KV, R2, Durable Objects or service bindings. Do not attach `commercecore.minhpham06.com`. Ignore any remote Previews Base config when creating the preview. The dedicated config leaves the existing strict local config check intact.

From repository root:

```sh
cd showcase
npm ci
npm run lint
npm run build
npm test
npm audit
(cd dist && shasum -a 256 -c ../docs/C4-ASSET-SHA256SUMS.txt)
cd ..
PYTHONDONTWRITEBYTECODE=1 python3 tools/consistency_lab/replay.py recheck showcase/evidence/inputs/inventory
PYTHONDONTWRITEBYTECODE=1 python3 tools/consistency_lab/replay.py recheck showcase/evidence/inputs/checkout
PYTHONDONTWRITEBYTECODE=1 python3 tools/consistency_lab/replay.py recheck showcase/evidence/inputs/synthetic
git diff --check
```

Repeat build and compare a fresh sorted SHA-256 manifest. If source/lockfile/assets change, repeat C4 and approve new hashes. Audit the exact dist, decoded report payloads and preview config. Confirm all evidence limitations remain visible and original capture revisions remain UNVERIFIED. Record revision, dirty-file inventory, Node/npm/Wrangler versions, all test results and skips. Do not regenerate historical evidence.

## Preview creation — only after explicit C5 authorization

Current official [Worker Previews guide](https://developers.cloudflare.com/workers/previews/get-started/) supports a preview for a new Worker without production deployment, requires Wrangler >=4.135 and a `previews` block. Installed 4.169 help confirms the following syntax. From `showcase/`, after reviewing the dedicated config:

```sh
WRANGLER_SEND_METRICS=false npx --no-install wrangler preview --config wrangler.preview.jsonc --worker-name commercecore-showcase --name c5-qualification --ignore-base-config --json
```

Capture returned Preview URL and immutable Deployment URL, deployment ID, account and uploaded asset inventory. Do not invent the account subdomain or deployment ID. The named URL points to latest preview, the unique deployment URL identifies exact deployment. Expect workers.dev only; preview URLs are public and workers.dev previews supply noindex. Inspect the resulting preview for bindings and domains before sharing. Abort on an unexpected target or any production/DNS operation.

## Hosted verification

Set `PREVIEW_URL` to the exact returned HTTPS URL; do not use a custom domain. Run the following against both returned URLs:

```sh
for route in / /walkthrough /architecture /lab /lab/debugger /evidence /reports/inventory /reports/checkout /reports/synthetic; do
  curl --fail --silent --show-error -D - -o /dev/null "$PREVIEW_URL$route"
done
curl --silent --show-error -o /dev/null -w '%{http_code}\n' "$PREVIEW_URL/missing"
curl --silent --show-error -o /dev/null -w '%{http_code}\n' "$PREVIEW_URL/assets/missing.js"
```

Require 200 for real pages, 404 for missing paths/assets, HTTPS, CSP matching `_headers`, nosniff, Referrer-Policy, Permissions-Policy and preview noindex. Download every file in C4-ASSET-SHA256SUMS.txt except `_headers` (control file, not public content) to a temporary directory and compare SHA-256; map `index.html` to `/` and nested `index.html` to their directory route to avoid canonical redirects. Download exact asset paths without modifying output. Fail on changed bytes, including embedded evidence.

Using Chrome at 390/768/1440 px, revisit all routes directly and refresh, exercise checkout retry/conflict/UNKNOWN lookup/review/reset, use keyboard and reduced motion, and inspect every report's operations/zoom/scroll/original-reduced view and print. Capture request/console logs: no unexpected requests, API traffic, third-party scripts/fonts, CSP violations or runtime errors. Historical inventory/checkout remain scoped PASS; synthetic remains exhaustive FAIL with constructed intervals and analytical 4-to-2 reduction. No global correctness, persisted-row uniqueness or production-readiness claims. JavaScript is required for the interactive application.

Repeat the before/after portfolio/CogniFlow public smoke checks and compare dashboard deployment IDs, bindings, routes, domains and DNS. Record unchanged state and account usage. Stop if any isolation check differs unexpectedly.

## Removal and recovery — C5 authorization must cover cleanup

To remove only the named preview and its deployments (syntax verified by installed help):

```sh
npx --no-install wrangler preview delete --config wrangler.preview.jsonc --worker-name commercecore-showcase --name c5-qualification
```

Verify the prompt's exact account/Worker/preview; never accept another target. Check the preview is gone and existing sites/configuration are unchanged. A failed preview can instead be replaced from previously approved source using the same preview command, after rebuilding and verifying its hashes. Do not delete the parent Worker or touch existing sites. Do not use production `wrangler rollback` for preview cleanup: [rollbacks](https://developers.cloudflare.com/workers/versions-and-deployments/rollbacks/) route 100% of production traffic to an older version and do not undo external resources. Asset version skew requires checking loaded pages after replacement/removal.

## Free limits and C6 gate

[Static asset requests are free and unlimited](https://developers.cloudflare.com/workers/static-assets/billing-and-limitations/); asset storage has no additional charge. [Free limits](https://developers.cloudflare.com/workers/platform/limits/) include 20,000 assets/version, 25 MiB/file and 100 Workers/account; script execution has separate 100,000 requests/day and 10 ms CPU limits. This build has 19 files/338,886 bytes and no script handler, so does not require paid compute/storage. Account-wide capacity still needs inspection. Optional [Workers Builds](https://developers.cloudflare.com/workers/ci-cd/builds/limits-and-pricing/) has Free 3,000 minutes/month, one concurrent build and 20-minute timeout; this manual local-build procedure does not require it.

C6 requires completed hosted checks, exact deployment evidence, unchanged existing sites, reviewed limitations, and separate owner authorization. [Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/) create DNS/certificates and take all paths at the attached hostname; C5 never attaches one. No production promotion, DNS change, custom domain or merge is implied by C4 or C5.
