# M15a offline oracle

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

No Spring classes, services, network calls, secrets, or application data are used. The CLI reads one local JSON file and writes JSON to stdout.
