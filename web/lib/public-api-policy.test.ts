import { describe, expect, it } from "vitest";
import { isAllowedPublicApi } from "./public-api-policy";

const id = "123e4567-e89b-12d3-a456-426614174000";

describe("public deployment API policy", () => {
  it("allows the Store and Order Inspector paths", () => {
    expect(isAllowedPublicApi("GET", "/api/commercecore/products")).toBe(true);
    expect(isAllowedPublicApi("POST", "/api/commercecore/carts")).toBe(true);
    expect(isAllowedPublicApi("PUT", `/api/commercecore/carts/${id}/items/DEMO-MUG`)).toBe(true);
    expect(isAllowedPublicApi("POST", `/api/commercecore/carts/${id}/checkout`)).toBe(true);
    expect(isAllowedPublicApi("GET", `/api/commercecore/orders/${id}/inspection`)).toBe(true);
  });

  it("blocks development, administrative, webhook, and direct reservation mutations", () => {
    expect(isAllowedPublicApi("POST", "/api/commercecore/dev/experiments/inventory-contention")).toBe(false);
    expect(isAllowedPublicApi("POST", "/api/commercecore/products")).toBe(false);
    expect(isAllowedPublicApi("POST", "/api/commercecore/reservations")).toBe(false);
    expect(isAllowedPublicApi("POST", "/api/commercecore/webhooks/payments")).toBe(false);
    expect(isAllowedPublicApi("GET", "/api/payment-provider/dev/provider/next-outcome")).toBe(false);
  });
});
