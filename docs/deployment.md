# Production deployment

This deployment runs the complete CommerceCore portfolio stack on one ordinary Linux VM. Only
Caddy publishes host ports. Next.js, both Java services, Kafka, and both PostgreSQL servers remain
on the private Compose network. The production profiles are `kafka,portfolio`; `dev` is never active.

## VM and DNS prerequisites

Use approximately 2 vCPU, 4 GB RAM, and 40 GB disk on Ubuntu 24.04 LTS. Before changing DNS, note the
VM's verified public IPv4 address and ensure TCP 80/443 can reach it. Do not invent an AAAA record.

Install Git and Docker Engine plus the Compose plugin using Docker's current official Ubuntu
instructions: https://docs.docker.com/engine/install/ubuntu/. Add the operator to the `docker` group
only if that access is intended; membership is root-equivalent. No host Java, Node, PostgreSQL, or
Kafka installation is needed.

A safe UFW sequence is:

```bash
sudo ufw allow OpenSSH        # do this before enabling UFW
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

Optionally restrict SSH to a trusted source only after testing another active session. Do not allow
3000, 5432, 5433, 8080, 8091, 9090, 9092, or 29092.

## Initial deployment

```bash
sudo install -d -o "$USER" -g "$USER" /opt/commercecore
git clone <REPOSITORY_URL> /opt/commercecore
cd /opt/commercecore
cp .env.production.example .env.production
openssl rand -base64 36   # generate the CommerceCore DB password
openssl rand -base64 36   # generate a different Payment DB password
chmod 600 .env.production
$EDITOR .env.production

docker compose --env-file .env.production -f compose.prod.yml config --quiet
docker compose --env-file .env.production -f compose.prod.yml build
docker compose --env-file .env.production -f compose.prod.yml up -d
```

Do not use example names such as `password`, `postgres`, `admin`, or `commercecore` as passwords.
The environment file must remain untracked and must not be pasted into logs or support output.

## Verify before DNS

```bash
docker compose --env-file .env.production -f compose.prod.yml ps
docker compose --env-file .env.production -f compose.prod.yml logs --tail=100 commercecore
docker compose --env-file .env.production -f compose.prod.yml logs --tail=100 payment-service
docker compose --env-file .env.production -f compose.prod.yml logs --tail=100 kafka
# Before DNS/TLS, temporarily set SITE_ADDRESS=:80 in .env.production, apply it, and test:
docker compose --env-file .env.production -f compose.prod.yml up -d reverse-proxy
curl --fail http://127.0.0.1/
```

All services should be healthy. The last command verifies the full reverse-proxy path before a
public certificate can exist. Confirm the Store lists the three seeded products and that a cart survives a normal page
refresh. Confirm `POST /api/commercecore/dev/experiments/inventory-contention` and
`/api/payment-provider/dev/provider/next-outcome` return 404 through the public origin.

Inspect host listeners with `sudo ss -ltnp`. Aside from administrative SSH, only `:80` and `:443`
should be public listeners from this stack. Compose uses `expose`, not `ports`, for every internal
service.

## Cloudflare activation

Only after local verification, create:

```text
Type: A
Name: commercecore
Content: <VM_PUBLIC_IP>
Proxy: Proxied
```

Change `SITE_ADDRESS` back to `commercecore.minhpham06.com`, run
`docker compose --env-file .env.production -f compose.prod.yml up -d reverse-proxy`, and watch the
Caddy logs until certificate issuance succeeds. Use Cloudflare SSL/TLS mode **Full (strict)** after
Caddy has obtained a valid origin certificate. Do not add AAAA until working IPv6 routing and
firewall rules are verified. Then verify the intended
public routes `/`, `/store`, and `/orders`; verify `/events`, `/failure-lab`, and `/experiments` show
read-only explanations; and repeat the blocked-development-API checks over HTTPS.

## Persistence, restart, and reboot

Named volumes contain CommerceCore PostgreSQL, Payment PostgreSQL, Kafka data, and Caddy TLS state.
The frontend and Java container filesystems are disposable. Test restart persistence:

```bash
docker compose --env-file .env.production -f compose.prod.yml restart
docker compose --env-file .env.production -f compose.prod.yml ps
```

Confirm an existing cart/order still reads correctly, seeded products were not duplicated, and
Kafka consumers reconnect. All services use `restart: unless-stopped`, so after a planned VM reboot
run `docker compose ... ps` and repeat route/health checks. Never use `down -v` for restart or update.

## Logs

```bash
docker compose --env-file .env.production -f compose.prod.yml logs -f commercecore
docker compose --env-file .env.production -f compose.prod.yml logs -f payment-service
docker compose --env-file .env.production -f compose.prod.yml logs -f kafka
docker compose --env-file .env.production -f compose.prod.yml logs -f frontend reverse-proxy
```

## Backups

Create protected files outside the repository and check exit status and size:

```bash
umask 077
source .env.production
docker compose --env-file .env.production -f compose.prod.yml exec -T postgres \
  pg_dump -U "$COMMERCECORE_DB_USER" -d commercecore -Fc > /var/backups/commercecore.dump
docker compose --env-file .env.production -f compose.prod.yml exec -T payment-postgres \
  pg_dump -U "$PAYMENT_DB_USER" -d payment_provider -Fc > /var/backups/payment-provider.dump
```

Copy backups off the VM and periodically test restoration in a separate environment. Database
volumes are not backups.

## Update and rollback

Record the deployed Git revision, take both database backups, inspect pending Flyway migrations,
then update without deleting volumes:

```bash
cd /opt/commercecore
git pull --ff-only
docker compose --env-file .env.production -f compose.prod.yml up -d --build
docker compose --env-file .env.production -f compose.prod.yml ps
```

For application rollback, check out the prior tested tag/commit and rebuild. Flyway migrations are
forward-only; do not assume an older application can read a migrated schema. If a release includes
an incompatible migration, rollback requires the documented release-specific data plan or restoring
both coordinated backups. Never make `docker compose down -v` part of normal rollback.

## Manual demo-data reset

The `portfolio` profile idempotently creates three missing demo SKUs. It never wipes visitor state or
restocks an existing depleted SKU. For a full reset, schedule downtime, take backups, explicitly
remove only the two database volumes, then start the stack to migrate and reseed. This is destructive
and is intentionally not presented as a routine command.
