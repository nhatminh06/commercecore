export type ConsumerEvidence = {
  name: string;
  purpose: string;
  receiptExists: boolean;
  processedAt: string | null;
  effectEvidence: string | null;
};

export type EventSummary = {
  eventId: string;
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  relatedOrderId: string | null;
  createdAt: string;
  publishedAt: string | null;
  publicationAttemptCount: number;
  published: boolean;
  consumers: ConsumerEvidence[];
};

export type EventInspection = EventSummary & {
  payload: unknown;
};

export type EventFilters = {
  search: string;
  eventType: string;
  publication: "ALL" | "PUBLISHED" | "PENDING";
  receipt: "ALL" | "RECORDED" | "MISSING";
};

export function filterEvents(events: EventSummary[], filters: EventFilters) {
  const search = filters.search.trim().toLowerCase();
  return events.filter((event) => {
    const matchesSearch = !search
      || event.eventId.toLowerCase().includes(search)
      || event.aggregateId.toLowerCase().includes(search)
      || event.relatedOrderId?.toLowerCase().includes(search);
    const matchesType = filters.eventType === "ALL" || event.eventType === filters.eventType;
    const matchesPublication = filters.publication === "ALL"
      || (filters.publication === "PUBLISHED" ? event.published : !event.published);
    const hasReceipt = event.consumers.some((consumer) => consumer.receiptExists);
    const matchesReceipt = filters.receipt === "ALL"
      || (filters.receipt === "RECORDED" ? hasReceipt : !hasReceipt);
    return Boolean(matchesSearch && matchesType && matchesPublication && matchesReceipt);
  });
}

export function shortId(value: string) {
  return `${value.slice(0, 8)}…`;
}

export function publicationLabel(event: Pick<EventSummary, "published">) {
  return event.published ? "PUBLISHED" : "PENDING";
}

export function receiptLabel(consumers: ConsumerEvidence[]) {
  const count = consumers.filter((consumer) => consumer.receiptExists).length;
  if (count === 0) return "NO RECEIPT";
  return `${count} RECEIPT${count === 1 ? "" : "S"}`;
}
