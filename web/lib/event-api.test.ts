import { afterEach, describe, expect, it, vi } from "vitest";

import { getEvent, getEvents } from "./event-api";

describe("event inspection API", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("loads bounded event evidence and an individual event", async () => {
    const summary = { eventId: "event-1", eventType: "ORDER_CREATED", published: false, consumers: [] };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([summary]), { status: 200, headers: { "content-type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...summary, payload: { orderId: "order-1" } }), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getEvents()).resolves.toEqual([summary]);
    await expect(getEvent("event/1")).resolves.toMatchObject({ payload: { orderId: "order-1" } });
    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/commercecore/dev/events", expect.any(Object));
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/commercecore/dev/events/event%2F1", expect.any(Object));
  });
});
