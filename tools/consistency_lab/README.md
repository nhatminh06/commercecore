# Consistency Lab evidence oracle and recorder

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

These checks follow `V1`, `V3`–`V5`, and `V10` migrations plus reservation/order workflow code. They do not verify inventory conservation, one-time restore, atomic checkout history, uniqueness of physical Kafka delivery, remote provider truth, or linearizability. A final snapshot cannot establish those histories. The checker has false-negative risk for violations erased before capture and false-positive risk if a fixture falsely attests completeness; v1 limits PASS to explicitly synthetic evidence. Database constraints may reject many mutations in a real database, but the oracle independently detects them in synthetic evidence. M16 must add a read-only collector with transactionally consistent per-database snapshots, bounded scope and row-count proof, run correlation, provenance, and explicit cross-database quiescence before accepting real evidence.

The v1 oracle uses no Spring classes, services, network calls, secrets, or application data. Its CLI reads one local JSON file and writes JSON to stdout.

## M16 PostgreSQL evidence v2

The test-only recorder is [recorder.py](recorder.py). It uses Python's standard library and a local `psql` client. No Python package or application runtime dependency is added. It accepts only an explicit loopback host, a database named `commercecore_lab_*`, and a `lab_reader` role. The database password comes only from `LAB_DB_PASSWORD`; it is never written to the bundle or printed on an error. Do not point this at shared development or production data. Create a dedicated, isolated test database first.

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

Qualify offline and, when the existing local `postgres:16` image and Docker daemon are available, against a disposable database:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/consistency_lab -p 'test_*.py'
LAB_POSTGRES_TEST=1 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/consistency_lab -p 'test_*.py'
```

The opt-in test creates and removes only its uniquely named container and applies the repository's V1–V11 Flyway SQL to that disposable database. It grants a SELECT-only role, proves an UPDATE is denied, tests coherent capture under a concurrent commit, rejects schema drift, and feeds the real isolated snapshot to the offline oracle. The existing v1 fixture remains valid. No provider database or cross-store property is captured in M16. A snapshot cannot prove inventory conservation, one-time restoration, checkout transaction history, exactly-once delivery, or linearizability. M17 may add controlled test network faults; M18–M19 need recorded operations and outcomes before historical claims are checkable.
