import { describe, expect, it } from "vitest";

import { filterEvents, publicationLabel, receiptLabel, type EventSummary } from "./event-evidence";

const events: EventSummary[] = [
  {
    eventId: "event-published", eventType: "ORDER_CREATED", aggregateType: "ORDER",
    aggregateId: "order-one", relatedOrderId: "order-one", createdAt: "2026-01-01T00:00:00Z",
    publishedAt: "2026-01-01T00:01:00Z", publicationAttemptCount: 1, published: true,
    consumers: [{ name: "proof", purpose: "proof", receiptExists: true, processedAt: "2026-01-01T00:02:00Z", effectEvidence: "PERSISTENT_RECEIPT_RECORDED" }],
  },
  {
    eventId: "event-pending", eventType: "PAYMENT_FAILED", aggregateType: "PAYMENT",
    aggregateId: "payment-two", relatedOrderId: "order-two", createdAt: "2026-01-02T00:00:00Z",
    publishedAt: null, publicationAttemptCount: 0, published: false,
    consumers: [{ name: "workflow", purpose: "workflow", receiptExists: false, processedAt: null, effectEvidence: null }],
  },
];

describe("event evidence", () => {
  it("filters by identifier, type, publication, and receipt evidence", () => {
    expect(filterEvents(events, { search: "order-two", eventType: "PAYMENT_FAILED", publication: "PENDING", receipt: "MISSING" }))
      .toEqual([events[1]]);
  });

  it("maps persisted evidence without overstating delivery", () => {
    expect(publicationLabel(events[0])).toBe("PUBLISHED");
    expect(publicationLabel(events[1])).toBe("PENDING");
    expect(receiptLabel(events[0].consumers)).toBe("1 RECEIPT");
    expect(receiptLabel(events[1].consumers)).toBe("NO RECEIPT");
  });
});
