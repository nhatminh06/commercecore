import { describe, expect, it } from "vitest";

import { explanationForStatus, toneForStatus } from "./order-inspection";

describe("order inspection state presentation", () => {
  it("treats UNKNOWN as ambiguity rather than failure", () => {
    expect(toneForStatus("UNKNOWN")).toBe("warning");
    expect(explanationForStatus("UNKNOWN")).toContain("does not have authoritative evidence");
    expect(explanationForStatus("UNKNOWN")).toContain("without re-authorizing");
  });

  it("presents REQUIRES_REVIEW as a distinct unsafe-repair state", () => {
    expect(toneForStatus("REQUIRES_REVIEW")).toBe("warning");
    expect(explanationForStatus("REQUIRES_REVIEW")).toContain("automatic business repair is unsafe");
  });

  it("maps terminal successful and unsuccessful states without hiding their text", () => {
    expect(toneForStatus("CONFIRMED")).toBe("success");
    expect(toneForStatus("FAILED")).toBe("danger");
    expect(toneForStatus("ACTIVE")).toBe("neutral");
  });
});
