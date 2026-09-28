import { describe, expect, it } from "vitest";

import { canReconcile, eventLink, failureScenarios, observedExpectedOutcome, orderLink, type ExperimentResult } from "./failure-lab";

function result(status: "AUTHORIZED" | "FAILED" | "UNKNOWN"): ExperimentResult {
  const scenario = failureScenarios.find((candidate) => candidate.expectedPaymentStatus === status)!;
  return { scenario, orderId: "order/id", paymentId: "payment-1", providerRequestId: "payment-1", operationPaymentStatus: status, publication: null, inspection: { order: { orderId: "order/id", status: "PENDING", total: 29.9, items: [] }, orderCreatedAt: "2026-01-01T00:00:00Z", payment: { paymentId: "payment-1", orderId: "order/id", amount: 29.9, status, providerReference: null, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" }, reservations: [], reconciliation: null } };
}

describe("Failure Lab scenario state", () => {
  it("offers reconciliation only for authoritative UNKNOWN observation", () => {
    expect(canReconcile(result("UNKNOWN"))).toBe(true);
    expect(canReconcile(result("FAILED"))).toBe(false);
    expect(canReconcile(result("AUTHORIZED"))).toBe(false);
  });

  it("checks expected results from inspection state and preserves link IDs", () => {
    expect(observedExpectedOutcome(result("UNKNOWN"))).toBe(true);
    expect(orderLink("order/id")).toBe("/orders/order%2Fid");
    expect(eventLink("order/id")).toBe("/events?aggregateId=order%2Fid");
  });
});
