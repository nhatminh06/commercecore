# Consistency Lab evidence oracle and recorder

M18's test-only seeded concurrent workload and versioned observed-history contract are documented in [M18 workload histories](M18-WORKLOAD-HISTORIES.md). Its operation histories are separate from M16 database evidence and are not oracle verdicts.

M19's [independent bounded history checker](M19-HISTORY-CHECKER.md) evaluates those histories against scoped inventory and checkout sequential models. It returns INCONCLUSIVE without an explicit exclusive-scope declaration and never treats a matching run ID as cross-store proof.

Run from the repository root:

```sh
python3 -m unittest discover -s tools/consistency_lab -p 'test_*.py'
python3 tools/consistency_lab/oracle.py tools/consistency_lab/fixtures/valid.json
```

The command prints one deterministic JSON object. Exit 2 means malformed evidence or an unreadable file. A check returns `PASS` only within the supplied, complete synthetic snapshot; `FAIL` identifies a violation in that snapshot; `INCONCLUSIVE` means required sections are missing, incomplete, or from different snapshots. These are checker qualification results, not claims about a live CommerceCore run.

## Evidence contract v1

Top level: integer `schema_version` (exactly 1), nonempty `run_id`, `evidence_kind` (exactly `synthetic_fixture`), and `sections`. Every supplied section has `source` (`commercecore.<section name>`), `scope` (`all_rows_at_one_db_snapshot`), nonempty `snapshot_id`, `completeness` (`attested` or `unknown`), and `rows`. For synthetic fixtures, `attested` means the fixture author declares the rows to be the whole modeled table. It is **not** a proof of real database capture. Sections used in a joined check must share a snapshot ID. Unknown sections, fields, identities, states, and value types are validation errors.

| Section | Row fields | Identity |
|---|---|---|
| `inventory` | `sku` string; `available_quantity` integer | `sku` |
| `orders` | `id` string; `status` `PENDING`, `CONFIRMED`, or `CANCELLED` | `id` |
| `inventory_reservations` | `id`, `sku` strings; `quantity` positive integer; `status` `ACTIVE`, `CONFIRMED`, `RELEASED`, or `EXPIRED`; `order_id` string or null | `id` |
| `checkout_idempotency` | `idempotency_key`, `cart_id`, `order_id` strings | `idempotency_key` |

All values are authoritative *within the synthetic fixture*, not derived by the checker. `order_id: null` represents a standalone reservation. The checker derives membership and state compatibility. It checks: (1) every inventory row is nonnegative; (2) every checkout mapping references an existing order; (3) every order-owned reservation references an order, a confirmed order has at least one reservation and all are confirmed, and a cancelled order retains no active or confirmed reservation. A standalone reservation has no order-state assertion. Empty attested sections pass only where the relevant property is vacuous in that synthetic scope.

These checks follow `V1`, `V3`–`V5`, and `V10` migrations plus reservation/order workflow code. They do not verify inventory conservation, one-time restore, atomic checkout history, uniqueness of physical Kafka delivery, cross-store payment correctness, or linearizability. A final snapshot cannot establish those histories. The checker has false-negative risk for violations erased before capture and false-positive risk if a fixture falsely attests completeness; v1 limits PASS to explicitly synthetic evidence. Database constraints may reject many mutations in a real database, but the oracle independently detects them in synthetic evidence.

The v1 oracle uses no Spring classes, services, network calls, secrets, or application data. Its CLI reads one local JSON file and writes JSON to stdout.

## M16 PostgreSQL evidence v2

The test-only recorder is [recorder.py](recorder.py). It uses Python's standard library and a local `psql` client. No Python package or application runtime dependency is added. Its CommerceCore mode accepts only an explicit loopback host, a database named `commercecore_lab_*`, and a `lab_reader` role. The database password comes only from `LAB_DB_PASSWORD`; it is never written to the bundle or printed on an error. Create a dedicated, isolated test database first.

```sh
python3 tools/consistency_lab/recorder.py \
  --run-id 00000000-0000-4000-8000-000000000016 \
  --host 127.0.0.1 --port 5432 --database commercecore_lab_example --role lab_reader \
  > /tmp/commercecore-lab-evidence.json
python3 tools/consistency_lab/oracle.py /tmp/commercecore-lab-evidence.json
```

Set `LAB_DB_PASSWORD` for the isolated reader in the process environment before running the command; avoid placing a password directly in shell history. The recorder refuses a privileged role. Grant only `CONNECT` on the isolated database, `USAGE` on `public`, and `SELECT` on the four tables; do not grant schema `CREATE`, table writes, role creation, or database creation. The read-only transaction is `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY`, followed by a 10-second statement timeout, provenance query, four bounded table queries, and `COMMIT`. A single PostgreSQL transaction snapshot covers all four table reads and counts. A missing table, unexpected column list, permission error, timeout, or connection failure ends capture with exit 2 and no evidence bundle.

| Table | Captured fields | Why |
|---|---|---|
| `inventory` | `sku`, `available_quantity` | Nonnegative stock |
| `orders` | `id`, `status` | Target identities and terminal states |
| `inventory_reservations` | `id`, `status`, nullable `order_id` | Ownership and order-state compatibility |
| `checkout_idempotency` | SHA-256 of `idempotency_key`, `order_id` | Distinct mapping identities and order references; raw key and cart ID are omitted |

The four actual table column lists are checked against migrations `V1`, `V3`, `V4`, `V5`, and `V10`. Capture scope is **all rows in these four tables in one isolated CommerceCore database**, not rows correlated to a particular application operation. The supplied UUID `run_id` labels this capture; it does not establish that every row belongs to that run. Each table query returns its row count and at most 10,001 ordered rows from the same snapshot. At most 10,000 rows are marked `verified`; overflow is `unknown` and the oracle returns `INCONCLUSIVE` for dependent properties. An empty table is verified only after the actual count and schema checks succeed. Output is stable JSON key order and row order for a fixed capture; timestamps and transaction IDs naturally differ across captures.

Evidence v2 has `schema_version: 2`, `evidence_kind: postgres_snapshot`, UUID `run_id`, `capture`, and `sections`. `capture` contains `source_id`, `host`, `port`, `database`, `role`, `captured_at` with offset, `transaction_snapshot`, `transaction_isolation`, and `transaction_read_only`. Each section has `source`, `scope`, `snapshot_id`, `columns`, `row_count`, `completeness` (`verified` or `unknown`), and `rows`. The oracle validates counts, columns, identities, state values, snapshot equality, and required provenance before a scoped PASS. A forged offline JSON file can still claim false provenance: v2 is independently **structurally validated**, not cryptographically authenticated. The raw idempotency key is not emitted, but an unsalted hash of a low-entropy key may be guessed; use random test-only keys and handle bundles as test data.

### Independent provider observation

The provider migration `payment-service/.../V1__create_provider_payments.sql` defines one authoritative table, `provider_payments`. Its `provider_request_id` is the primary key. `PaymentService` passes `payments.id` to `GrpcPaymentProviderClient.authorize`; the gRPC service passes that request ID to `ProviderLedger.authorize`, which stores it as `provider_request_id`. This is the only supported payment identity correlation. The provider recorder captures only `provider_request_id`, `status` (`AUTHORIZED` or `DECLINED`), and nullable `provider_reference`. It checks the full five-column table schema but omits `amount` and `created_at`. No customer records or credentials are emitted.

Create a separate isolated `payment_provider_lab_*` database with the provider V1 migration. Grant a dedicated `lab_provider_reader` only `CONNECT` on that database, `USAGE` on `public`, and `SELECT` on `provider_payments`. Set `LAB_DB_PASSWORD` to that role's password, then run a **separate invocation**:

```sh
python3 tools/consistency_lab/recorder.py --store provider \
  --run-id 00000000-0000-4000-8000-000000000016 \
  --host 127.0.0.1 --port 5432 --database payment_provider_lab_example --role lab_provider_reader \
  > /tmp/provider-lab-evidence.json
python3 tools/consistency_lab/oracle.py /tmp/provider-lab-evidence.json
```

The provider bundle retains `schema_version: 2`, uses `evidence_kind: provider_postgres_snapshot` and `source_id: provider-local-test`, and has one `provider_payments` section. Its own `REPEATABLE READ READ ONLY` transaction captures schema, count, and at most 10,001 ordered rows. Counts above 10,000 yield `unknown` completeness. The recorder rejects absent or changed schema, SQL errors, writable privileges, invalid target, and missing provenance. The oracle validates UUIDs, outcomes, reference/status shape, count, and snapshot identity. `provider_payment_observation: PASS` means only that this isolated provider table was fully observed and structurally valid. `cross_store_payment_state` always remains `INCONCLUSIVE`: the four-table CommerceCore bundle does not include local `payments`, and even future separate captures cannot claim an atomic cross-database boundary from matching `run_id` or wall clocks. A local `UNKNOWN` with provider `AUTHORIZED` is expected after a lost response, not automatically a violation.

**M16 verdict: PASS for authoritative, bounded database snapshot evidence.** This is the explicitly scoped M16 acceptance decision recorded in the roadmap. The original proposal also included Kafka/receipt evidence and invocation/result correlation; those remain unimplemented and deferred. This verdict does not cover cross-store payment consistency, event delivery history, replay, or linearizability.

Qualify offline and, when the existing local `postgres:16` image and Docker daemon are available, against a disposable database:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/consistency_lab -p 'test_*.py'
LAB_POSTGRES_TEST=1 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/consistency_lab -p 'test_*.py'
```

The opt-in test creates and removes only its uniquely named container, applies the repository's V1–V11 CommerceCore SQL and provider V1 SQL to separate disposable databases, and uses distinct SELECT-only roles. It proves UPDATE denial, rejects an accidentally granted INSERT privilege, tests provider permission denial and schema drift, checks deterministic row serialization, and feeds real isolated snapshots to the oracle. The existing v1 fixture remains valid. GitHub Actions currently runs `python -m unittest discover` without `LAB_POSTGRES_TEST=1`, so hosted CI executes the offline subset and skips these six PostgreSQL integration tests: `test_complete_capture_and_read_only_role`, `test_provider_capture_and_read_only_role`, `test_provider_partial_and_deterministic_evidence`, `test_provider_schema_drift_and_permission_denial`, `test_rejects_schema_change`, and `test_snapshot_survives_concurrent_commit`. The locally opted-in suite is the real capture qualification. A snapshot cannot prove inventory conservation, one-time restoration, checkout transaction history, exactly-once delivery, cross-store atomicity, or linearizability. M17 may add controlled test network faults; M18–M19 need recorded operations and outcomes before historical claims are checkable.
