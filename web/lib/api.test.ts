import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, request } from "./api";

describe("CommerceCore request helper", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("preserves backend status, code, payload, and message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: "insufficient_stock", message: "Not enough stock" }),
      { status: 409, headers: { "content-type": "application/json" } },
    )));

    await expect(request("/checkout")).rejects.toMatchObject<ApiError>({
      name: "ApiError",
      status: 409,
      code: "insufficient_stock",
      message: "Not enough stock",
      body: { code: "insufficient_stock", message: "Not enough stock" },
    });
  });

  it("handles successful empty responses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    await expect(request<void>("/cart", { method: "DELETE" })).resolves.toBeUndefined();
  });
});
