# Deployment readiness

Phase 1 prepares the stack but does not provision a VM, change DNS, or request a certificate.

## Locally verifiable

- [x] production environment validator passes with an isolated local env file
- [x] production Compose configuration renders successfully
- [x] production images build
- [x] all production Compose services reach running/healthy state
- [x] `/healthz` reports `UP`
- [x] frontend and deterministic three-product catalog load
- [x] development, webhook, and administrative mutation routes are rejected publicly
- [x] canonical cart → checkout → payment → order inspection flow succeeds
- [x] restart preserves order data and does not duplicate seeded products
- [x] default Compose configuration publishes only ingress ports 80 and 443
- [x] Caddy starts with the local HTTP site address and proxies frontend/API health traffic
- [ ] `scripts/demo-final.sh` reproduces the local-only UNKNOWN → reconciliation flow

## Phase 2 only

- [ ] VM sizing, disk, firewall, and backup destination verified
- [ ] DNS A record points to the verified VM address
- [ ] Caddy obtains a valid certificate for `commercecore.minhpham06.com`
- [ ] HTTPS smoke test passes from outside the VM
- [ ] PostgreSQL, Kafka, frontend, CommerceCore, and Payment Service ports are not publicly reachable
- [ ] database backup and restore rehearsal completed
