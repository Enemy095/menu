import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  probePublicApiReadiness: vi.fn(),
  isPublicApiUnavailable: vi.fn(),
}));

vi.mock("@/shared/api/public-api", () => apiMocks);

import { middleware as proxy } from "./middleware";

beforeEach(() => {
  apiMocks.probePublicApiReadiness.mockResolvedValue(undefined);
  apiMocks.isPublicApiUnavailable.mockReturnValue(false);
});

describe("public route availability proxy", () => {
  it("continues to the ISR page when dependencies are healthy", async () => {
    const response = await proxy();

    expect(response.status).toBe(200);
    expect(response.headers.get("x-middleware-next")).toBe("1");
  });

  it("rewrites dependency outages with an actual HTTP 503 and noindex headers", async () => {
    const error = new Error("dependency unavailable");
    apiMocks.probePublicApiReadiness.mockRejectedValue(error);
    apiMocks.isPublicApiUnavailable.mockReturnValue(true);

    const response = await proxy();

    expect(response.status).toBe(503);
    expect(response.headers.get("x-robots-tag")).toBe("noindex, nofollow");
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("content-type")).toBe("text/html; charset=utf-8");
    await expect(response.text()).resolves.toContain("Меню временно недоступно");
  });
});
