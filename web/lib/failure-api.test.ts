import { afterEach, describe, expect, it, vi } from "vitest";

import { configureProvider, reconcilePayment } from "./failure-api";

describe("Failure Lab APIs", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("configures one provider outcome through the Payment Service development proxy", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    await configureProvider("TIMEOUT_AFTER_PROCESSING");
    expect(fetchMock).toHaveBeenCalledWith("/api/payment-provider/dev/provider/next-outcome", expect.objectContaining({ method: "POST", body: JSON.stringify({ outcome: "TIMEOUT_AFTER_PROCESSING" }) }));
  });

  it("reconciles the same payment identity through CommerceCore", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ paymentId: "payment-1", status: "RESOLVED" }), { status: 200, headers: { "content-type": "application/json" } })));
    await reconcilePayment("payment/1");
    expect(fetch).toHaveBeenCalledWith("/api/commercecore/dev/payments/payment%2F1/reconcile", expect.objectContaining({ method: "POST" }));
  });
});
