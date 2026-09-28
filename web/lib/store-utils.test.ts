import { afterEach, describe, expect, it, vi } from "vitest";

import {
  clearStoredCartId,
  formatMoney,
  generateCheckoutKey,
  isValidQuantity,
  multiplyMoney,
  readStoredCartId,
  storeCartId,
} from "./store-utils";

describe("Store Simulator utilities", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("generates UUID-based checkout keys", () => {
    vi.stubGlobal("crypto", { randomUUID: () => "123e4567-e89b-12d3-a456-426614174000" });
    expect(generateCheckoutKey()).toBe("demo-checkout-123e4567-e89b-12d3-a456-426614174000");
  });

  it("persists only the active server cart identifier", () => {
    const values = new Map<string, string>();
    vi.stubGlobal("window", {
      localStorage: {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
      },
    });

    storeCartId("cart-123");
    expect(readStoredCartId()).toBe("cart-123");
    expect([...values.values()]).toEqual(["cart-123"]);
    clearStoredCartId();
    expect(readStoredCartId()).toBeNull();
  });

  it("validates positive whole quantities", () => {
    expect(isValidQuantity(1)).toBe(true);
    expect(isValidQuantity(0)).toBe(false);
    expect(isValidQuantity(-1)).toBe(false);
    expect(isValidQuantity(1.5)).toBe(false);
  });

  it("uses exact integer minor units for display multiplication", () => {
    expect(multiplyMoney("89.99", 3)).toBe(26997n);
    expect(formatMoney(26997n)).toBe("$269.97");
  });
});
