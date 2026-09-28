import { describe, expect, it } from "vitest";
import { boundedInteger, eventLink, orderLink, resultLabel } from "./experiment-api";

describe("Concurrency Lab evidence", () => {
  it("enforces safe integer bounds", () => {
    expect(boundedInteger(2, 2, 200)).toBe(true);
    expect(boundedInteger(200, 2, 200)).toBe(true);
    expect(boundedInteger(1, 2, 200)).toBe(false);
    expect(boundedInteger(20.5, 2, 200)).toBe(false);
    expect(boundedInteger(201, 2, 200)).toBe(false);
  });

  it("maps complete evidence to PASS or FAIL and never passes incomplete work", () => {
    expect(resultLabel({ experimentId: "x", complete: true, invariantPreserved: true, durationMs: 1 })).toBe("PASS");
    expect(resultLabel({ experimentId: "x", complete: true, invariantPreserved: false, durationMs: 1 })).toBe("FAIL");
    expect(resultLabel({ experimentId: "x", complete: false, invariantPreserved: true, durationMs: 1 })).toBe("INCOMPLETE");
  });

  it("encodes domain evidence links", () => {
    expect(orderLink("order/id")).toBe("/orders/order%2Fid");
    expect(eventLink("event/id")).toBe("/events/event%2Fid");
  });
});
