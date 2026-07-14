import type { CreateOrderRequest } from "@e-menu/api-contract/generated";
import { afterEach, describe, expect, it, vi } from "vitest";

import { submitPublicOrder } from "./orders-api";

const request = {
  orderType: "pickup",
  customer: { name: "Анна", phone: "+79990000000" },
  fulfillment: {},
  items: [{ menuItemId: "pizza", quantity: 1, modifiers: [] }],
  clientMenuVersion: 1,
} as unknown as CreateOrderRequest;

describe("generated OrdersApi adapter", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("forwards the stable idempotency key and exposes replay + authoritative total", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          orderId: "0f40f8a4-2612-4df2-a5e8-812de01740c0",
          orderNumber: "A-42",
          status: "new",
          serverTotalMinor: 1250,
          currency: "RUB",
          serverReceivedAt: "2026-07-14T12:00:00Z",
          needsConfirmation: false,
        }),
        { status: 201, headers: { "Content-Type": "application/json", "Idempotency-Replayed": "true" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);
    const key = "4bd30558-5a2d-4d36-b2e0-51e37b426a3f";

    const result = await submitPublicOrder(key, request);

    expect(result.replayed).toBe(true);
    expect(result.order.serverTotalMinor).toBe(1250);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/public/orders"),
      expect.objectContaining({ headers: expect.objectContaining({ "Idempotency-Key": key }) }),
    );
  });

  it("maps a contract error envelope without trusting arbitrary errors", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "MENU_CHANGED",
            message: "menu changed",
            details: [],
            traceId: "trace-1",
          }),
          { status: 409, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(submitPublicOrder(crypto.randomUUID(), request)).rejects.toMatchObject({
      kind: "api",
      status: 409,
      code: "MENU_CHANGED",
    });
  });

  it("rejects successful JSON outside exact OpenAPI bounds", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            orderId: "0f40f8a4-2612-4df2-a5e8-812de01740c0",
            orderNumber: "x".repeat(41),
            status: "new",
            serverTotalMinor: 1_000_000_000_001,
            currency: "RUB",
            serverReceivedAt: "2026-07-14T12:00:00Z",
            needsConfirmation: false,
            unexpected: true,
          }),
          { status: 201, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );
    await expect(submitPublicOrder(crypto.randomUUID(), request)).rejects.toMatchObject({
      kind: "invalid-response",
    });
  });

  it("does not interpret an oversized or non-strict error envelope", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "MENU_CHANGED",
            message: "x".repeat(501),
            details: [],
            traceId: "trace-1",
            unexpected: true,
          }),
          { status: 409, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );
    const error = await submitPublicOrder(crypto.randomUUID(), request).catch((cause: unknown) => cause);
    expect(error).toMatchObject({ kind: "api", status: 409 });
    expect(error).not.toHaveProperty("code", "MENU_CHANGED");
  });
});
