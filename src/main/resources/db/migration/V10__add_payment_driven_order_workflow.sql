-- Orders may now resolve out of PENDING once a payment outcome is known. V4's constraint only
-- ever allowed PENDING (order confirmation/cancellation didn't exist yet); this widens it rather
-- than editing V4, which stays an accurate record of what was true at that milestone.
ALTER TABLE orders
    DROP CONSTRAINT orders_status_check;

ALTER TABLE orders
    ADD CONSTRAINT orders_status_check CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'));

-- New domain events describing the committed facts this milestone's workflow produces. Same
-- reasoning as V8: only real, already-implemented facts get a name here.
ALTER TABLE outbox_events
    DROP CONSTRAINT outbox_events_event_type_check;

ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_event_type_check CHECK (event_type IN
        ('ORDER_CREATED', 'PAYMENT_AUTHORIZED', 'PAYMENT_FAILED', 'ORDER_CONFIRMED', 'ORDER_CANCELLED'));

-- Deliberately a separate table from kafka_event_receipts (V9): that one belongs to the
-- technical proof consumer's group (commercecore-proof-consumer); this one belongs to the order
-- workflow consumer's group (commercecore-order-workflow). Deduplication identity is scoped to a
-- consumer's own side effect, not globally to the Kafka topic — two different consumer groups
-- must each be free to process event E1 once for their own purpose. See docs/order-workflow.md.
CREATE TABLE order_workflow_event_receipts (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    payment_id   UUID NOT NULL,
    order_id     UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
