# CommerceCore production release and maintenance

Production: https://commercecore.minhpham06.com/
Source: https://github.com/nhatminh06/commercecore

The public release is a static-first browser demonstration: an in-memory checkout simulation,
reviewed recorded local backend executions, and a separately labeled synthetic checker
counterexample. It does not host the Java, PostgreSQL, Kafka, or gRPC backend.

## Qualified release

- Source revision: `43a969f5e08a3f918936ea697f8619a1bc939efc`.
- Account: Nhat Minh; Workers Free, with no new paid subscription detected.
- Worker: `commercecore-showcase`; Custom Domain: `commercecore.minhpham06.com`.
- Production version: `4f674be2-0111-478e-bc27-21857e920288`.
- Deployment: `391bb1ca-9af6-4465-bfc7-9cdb102ce2e5`.
- Manifest: [C4-ASSET-SHA256SUMS.txt](C4-ASSET-SHA256SUMS.txt).
- Manifest SHA-256: `3f697e0ab6803e22b848ffb4a6036d4c1fd4d80ee9064db981226932bfa6b3a4`.
- Distribution: 19 files, 338,886 bytes; 18 public assets and processed `_headers`.
- Preserved production endpoint: https://commercecore-showcase.mphamnhat2006.workers.dev/
- Preserved C5 preview: https://c5-qualification-commercecore-showcase.mphamnhat2006.workers.dev/

Fresh C6 recovery qualification on November 27, 2026 (America/Chicago) ran the existing hosted
Chrome suite: **35 passed, 0 failed, 0 skipped**. All **18/18 public asset hashes**, including ten
HTML responses, matched the manifest. Six routes and reloads, genuine 404s, security headers,
three standalone M21 reports, provenance labels, checkout/recovery scenarios, keyboard behavior,
and 390/768/1440 px layouts passed. Browser checks observed no Insights injection, CSP errors,
unexpected external requests, or backend connections. These are hosted demonstration checks,
not a fresh backend correctness experiment or production-readiness certification.

Read-only comparisons with retained preflight metadata found unchanged portfolio Pages deployment
`a77eab4c-ca0d-4a91-9a34-f32b3a2a16b6`, domains and bindings; unchanged CogniFlow version
`22909605-0e00-4189-89a8-0406342ce256`, domains, D1/rate-limit bindings and deployment history;
and unchanged CommerceCore deployment, Worker routes and subscription metadata. DNS was checked
during domain activation: the only addition was the managed CommerceCore Worker record. The
recovery test rerun did not independently repeat the DNS dashboard inventory.

## Evidence limits

Historical capture Git revisions remain **UNVERIFIED**. Preserve original run IDs and
artifact-reported November 24, 2026 timestamps; checker/recheck revisions are separate provenance.
Observed PASS results require the documented exclusive-scope and model assumptions. The checkout
example does not prove persisted-row uniqueness. The synthetic FAIL is a constructed checker
counterexample, not an observed CommerceCore defect. No global linearizability, exactly-once Kafka
transport, customer usage, or production readiness is claimed. See [evidence review](../evidence/REVIEW.md)
and [catalog](../evidence/catalog.json) for exact source and derivative hashes.

## RUM injection incident

After Custom Domain attachment, Cloudflare automatic Web Analytics modified all ten HTML
responses with an Insights beacon: 5/35 tests passed and only 8/18 public hashes matched. CSP
correctly blocked the external script. In-memory removal of that injection reproduced the
approved HTML hashes; the application release was not redeployed.

The account's analytics site for `minhpham06.com` used automatic setup, enabled excluding EU
visitors. Recovery requalification subsequently confirmed the injection absent and all hashes
restored. The exact applied remediation, active rule ID, expression, and rule ordering remain
**UNVERIFIED** through available read-only access; do not claim this document proves which setting
was changed. Existing CLI reads of rules/RUM configuration returned HTTP 403.

The investigated proposal was a Configuration Rule with expression
`(http.host eq "commercecore.minhpham06.com")`, action `set_config`, and `disable_rum: true`.
That expression is a proposal, not an independently verified active rule. Cloudflare documents
[the RUM setting and precedence](https://developers.cloudflare.com/rules/configuration-rules/settings/)
and [10 Configuration Rules on Free](https://developers.cloudflare.com/rules/configuration-rules/).
Do not disable zone-wide analytics or alter portfolio/CogniFlow analytics.

## Maintenance and rollback

1. Before a release, inspect Git status/stashes and verify the intended source and all manifest
   hashes from a clean checkout. Review every changed public artifact and preserve evidence bytes.
2. Recheck account/plan, CommerceCore bindings, DNS/routes and existing-service baselines through
   authorized read-only access. Verify the active RUM rule ID/expression/order in the dashboard
   before maintaining or rolling back that rule; 403 means unverified, not absent.
3. Obtain separate approval for any upload, production promotion, domain/DNS change or rollback.
   These documentation changes authorize none of those actions.
4. After an approved change, rerun the 35 hosted tests and all 18 public hashes. Keep CSP
   `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'; img-src 'self';
   font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'` and the existing
   nosniff, Referrer-Policy and Permissions-Policy headers. Do not allow the beacon as a workaround.
5. On failure, stop further release operations. Record version, deployment, URL and exact failing
   hashes/checks. Do not automatically disable public access or modify another service.
6. Domain rollback, only after approval: remove exactly `commercecore.minhpham06.com` from this
   Worker's Custom Domains and confirm its managed DNS record is removed. Preserve the Worker,
   production workers.dev endpoint and C5 preview; do not delete certificates independently.
7. RUM-rule rollback, only after identifying and approving the exact rule: disable only the
   CommerceCore exclusion. This can restore injection and qualification failures. Do not change
   the shared analytics site's setup. Application-version rollback requires separately approved
   known-good assets/version; the C5 preview ID is not a production deployment ID.

C7 prepares local portfolio links and documentation only. Portfolio `nhatminh06/myprofile` is
connected to Pages: pushes to `main` deploy production and other branches can create previews.
Review every proposed diff before authorizing a push/PR. No C7 deployment is implied.

## C7 local closeout checks

On November 28, 2026, the local portfolio registry validator and six existing Node tests passed;
JavaScript syntax and both repositories' diff whitespace checks passed. Portfolio project/card
uniqueness, unchanged other entries, and 375/768/1024/1440 px layouts were checked. The portfolio
is plain static HTML/CSS/JavaScript and defines no lint, typecheck, or build command.

A fresh production smoke check loaded all six routes and all three reports with no injected
Insights script or external application asset requests. It found an automatic browser request
for `/favicon.ico` returning 404. This is a remaining cosmetic console warning, not a CSP
violation; no approved asset was changed to address it. The full 35-test/18-hash qualification
above describes the earlier fresh recovery run, not a new full run during C7.

The local portfolio diff changes only its CommerceCore registry entry, project card and case
study. Existing featured ordering, shared styles, deployment configuration and other projects
remain unchanged. No existing project-screenshot pattern was found, so no image was added.
