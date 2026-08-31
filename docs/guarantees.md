# Evidence-backed guarantees

This document states only guarantees exercised by the repository. “Exactly once” below applies to
a local logical effect protected by PostgreSQL, never to transport delivery.

| Area | Guarantee | Mechanism | Failure evidence | Limitation |
|---|---|---|---|---|
| Inventory | Supported consumption cannot make availability negative. | Conditional PostgreSQL `UPDATE ... WHERE available_quantity >= ?` plus a nonnegative check constraint. | 100 buyers racing for 10 units produce 10 winners, 90 failures, stock 0. | No allocation fairness; direct database writes outside supported paths are out of scope. |
| Reservation | An ACTIVE reservation owns stock; restoration happens at most once. | Reservation row lock and status transition in the same transaction as inventory restoration. | Concurrent double release ends at stock 5, not 7; confirm/release race has one consistent terminal state. | Expiration is explicit, not automatically scheduled. |
| Checkout | All cart lines, reservations, order, idempotency mapping, and `ORDER_CREATED` intent commit together. | One CommerceCore PostgreSQL transaction. | A-stock 5/B-stock 0 checkout leaves stock 5/0 and creates no checkout state or outbox event. | No transaction spans the remote provider or Kafka. |
| Checkout retry | One key and cart identify one logical checkout. | Transaction-scoped PostgreSQL advisory lock plus primary-key mapping. | 20 sequential and 20 concurrent calls return one order and consume stock once. | Keys have no TTL or cleanup policy. |
| Payment observation | Ambiguous authorization is `UNKNOWN`, never guessed as failure. | Three-phase local persistence around the remote call and explicit gRPC status mapping. | Real deadline and unavailable-service tests leave one internally consistent `UNKNOWN` payment. | `UNKNOWN` requires explicit reconciliation; no scheduler exists. |
| Remote provider | One stable request identity cannot double-authorize. | CommerceCore payment UUID, provider primary key, and transaction-scoped advisory lock. | 20 concurrent TCP RPCs create one row/reference; same ID/different amount is rejected. | Simulated provider, plaintext gRPC, one configured endpoint. |
| Webhook | Repeating one provider event ID has one logical effect. | Webhook-event primary key plus payment row locking and idempotent transitions. | 20 sequential and 20 concurrent identical deliveries produce one receipt/transition/outcome event. | No signature verification or provider authentication. |
| Outbox | Committed publication intent is atomic with its business fact and remains retryable. | Outbox insert joins the business transaction; claim/publish/mark uses stable IDs. | Fail-before-accept leaves no external delivery; accept-before-mark delivers the same event ID twice on retry. | Publication is at least once; no automatic scheduler or cleanup policy. |
| Kafka | Duplicate physical records do not duplicate the proof consumer's logical processing. | Consumer-group-specific PostgreSQL receipt primary key; acknowledge after local commit. | Two records with one event ID produce one receipt; both pre-commit and post-commit failure windows recover safely. | One local broker, replication factor 1, no global ordering guarantee. |
| Order workflow | Payment outcome mutates order/reservations once. | Workflow receipt, locked order transition, reservations, inventory effect, and resulting outbox event share one transaction. | Duplicate AUTHORIZED confirms once; duplicate FAILED cancels and restores inventory once. | Only payment AUTHORIZED/FAILED events are handled. |
| Reconciliation | Provider truth is queried without reauthorization. | `LookupPayment` followed by a locked local applier transaction. | Repeated/concurrent calls and a webhook race keep authorization count unchanged and emit one outcome event. | Contradictions need operator review; no automatic refund. |
| Lost reservation | Authorization discovered after stock ownership is lost cannot oversell automatically. | Reservation-state check before order confirmation and durable reconciliation review state. | Payment becomes AUTHORIZED, case becomes `REQUIRES_REVIEW`, order stays PENDING, reservation EXPIRED, stock stays restored. | No operator UI, re-reservation, or automatic refund. |

## Database invariant inventory

CommerceCore migrations enforce unique SKU, nonnegative stock and money, positive cart/order/
reservation quantities, one cart line per SKU, valid reservation/order/payment/reconciliation
states, one payment per order, unique checkout key, unique webhook event ID, unique consumer receipt
IDs, and constrained outbox aggregate/event types. Payment Service enforces one row per provider
request, nonnegative exact-decimal amount, definitive provider statuses only, and reference/status
consistency.

Application-level PostgreSQL mechanisms add conditional inventory updates, reservation/order/payment
row locks, transaction-scoped advisory locks for checkout and provider identity, and `FOR UPDATE
SKIP LOCKED` outbox claims. Existing migration history is unchanged; review found no defensive
schema migration justified for this milestone.

