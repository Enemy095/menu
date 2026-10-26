import { afterEach, describe, expect, it, vi } from "vitest";

import { POST } from "./route";

vi.mock("@/shared/api/public-api", () => ({
  publicApiOrigin: () => "http://backend.test",
}));

describe("same-origin order transport", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("forwards only the canonical body and idempotency header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ orderNumber: "A-42" }), {
        status: 201,
        headers: { "Content-Type": "application/json", "Idempotency-Replayed": "true" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const key = "4bd30558-5a2d-4d36-b2e0-51e37b426a3f";
    const request = new Request("http://frontend.test/api/v1/public/orders", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": key, Cookie: "secret=1" },
      body: JSON.stringify({ orderType: "pickup" }),
    });

    const response = await POST(request);

    expect(response.status).toBe(201);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(response.headers.get("Idempotency-Replayed")).toBe("true");
    expect(fetchMock).toHaveBeenCalledWith(
      "http://backend.test/api/v1/public/orders",
      expect.objectContaining({
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "Idempotency-Key": key,
        },
      }),
    );
  });

  it("rejects an invalid key before contacting the backend", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const response = await POST(
      new Request("http://frontend.test/api/v1/public/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": "not-a-uuid" },
        body: "{}",
      }),
    );
    expect(response.status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects an oversized declared length before consuming the request stream", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    let pulls = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulls += 1;
        controller.enqueue(new TextEncoder().encode("{}"));
        controller.close();
      },
    });
    const request = new Request("http://frontend.test/api/v1/public/orders", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": String(64 * 1024 + 1),
          "Idempotency-Key": crypto.randomUUID(),
        },
        body,
        // Node requires duplex for a streaming request body; browsers ignore this extension.
        duplex: "half",
      } as RequestInit & { duplex: "half" });
    const response = await POST(request);
    expect(response.status).toBe(413);
    expect(request.bodyUsed).toBe(false);
    expect(pulls).toBeLessThanOrEqual(1);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("stops an undeclared streaming body as soon as the byte cap is crossed", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const response = await POST(
      new Request("http://frontend.test/api/v1/public/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() },
        body: "x".repeat(64 * 1024 + 1),
      }),
    );
    expect(response.status).toBe(413);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
