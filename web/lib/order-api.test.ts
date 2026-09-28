import { afterEach, describe, expect, it, vi } from "vitest";

import { getOrderInspection } from "./order-api";

describe("order inspection API", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("returns authoritative nested inspection state", async () => {
    const body = {
      order: { orderId: "order-1", status: "PENDING", total: 39.9, items: [] },
      orderCreatedAt: "2026-01-01T00:00:00Z",
      payment: { paymentId: "payment-1", orderId: "order-1", amount: 39.9, status: "UNKNOWN" },
      reservations: [{ id: "reservation-1", sku: "KEY-001", quantity: 2, status: "ACTIVE" }],
      reconciliation: null,
    };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(body), {
      status: 200,
      headers: { "content-type": "application/json" },
    })));

    const result = await getOrderInspection("order-1");

    expect(result.payment?.status).toBe("UNKNOWN");
    expect(result.reservations[0]).toMatchObject({ sku: "KEY-001", status: "ACTIVE" });
    expect(fetch).toHaveBeenCalledWith("/api/commercecore/orders/order-1/inspection", expect.any(Object));
  });
});
