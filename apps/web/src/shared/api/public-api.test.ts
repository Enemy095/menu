import { afterEach, describe, expect, it, vi } from "vitest";

import { rawConfiguration, rawMenu } from "@/test/fixtures";

import {
  getPublicPageData,
  probePublicApiReadiness,
  PublicApiConsistencyError,
  PublicApiContractError,
  PublicApiHttpError,
} from "./public-api";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.MENU_API_BASE_URL;
});

describe("public API boundary", () => {
  it("uses canonical endpoints, validates JSON and returns generated client models", async () => {
    process.env.MENU_API_BASE_URL = "http://api.test";
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      void init;
      const url = input.toString();
      return Promise.resolve(jsonResponse(url.endsWith("/config") ? rawConfiguration : rawMenu));
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await getPublicPageData();

    expect(result.configuration.orderTypes).toBeInstanceOf(Set);
    expect(result.menu?.publishedAt).toBeInstanceOf(Date);
    expect(fetchMock.mock.calls.map(([input]) => input.toString())).toEqual([
      "http://api.test/api/v1/public/config",
      "http://api.test/api/v1/public/menu",
    ]);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init).toMatchObject({
        cache: "force-cache",
        next: { revalidate: 60 },
      });
    }
  });

  it("treats an unpublished menu as an explicit empty state", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string | URL | Request) =>
        Promise.resolve(
          input.toString().endsWith("/config")
            ? jsonResponse(rawConfiguration)
            : jsonResponse({ code: "MENU_NOT_FOUND" }, 404),
        ),
      ),
    );

    await expect(getPublicPageData()).resolves.toMatchObject({ menu: null });
  });

  it("maps service unavailability without exposing a response body", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(jsonResponse({ internal: "do not expose" }, 503))),
    );

    await expect(getPublicPageData()).rejects.toEqual(new PublicApiHttpError(503, "config"));
  });

  it("probes current canonical API readiness without using the data cache", async () => {
    process.env.MENU_API_BASE_URL = "https://api.test";
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      void input;
      void init;
      return Promise.resolve(jsonResponse({ ignored: true }));
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(probePublicApiReadiness()).resolves.toBeUndefined();

    expect(fetchMock.mock.calls.map(([input]) => input.toString())).toEqual([
      "https://api.test/api/v1/public/config",
      "https://api.test/api/v1/public/menu",
    ]);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init).toMatchObject({
        cache: "no-store",
        headers: { Accept: "application/json", "X-Menu-Readiness": "1" },
      });
    }
  });

  it("rejects a successful response that violates the contract", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse({ restaurantId: "invalid" }))));

    await expect(getPublicPageData()).rejects.toBeInstanceOf(PublicApiContractError);
  });

  it("rejects a base URL that is not a pure origin before transport", async () => {
    process.env.MENU_API_BASE_URL = "https://api.test/internal?source=web";
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await expect(getPublicPageData()).rejects.toThrow("must be a pure HTTP(S) origin");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects configuration and menu snapshots from different restaurants", async () => {
    const mismatchedMenu = {
      ...rawMenu,
      restaurantId: "54d773af-51db-4542-a09b-25b7b78996cf",
    };
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string | URL | Request) =>
        Promise.resolve(jsonResponse(input.toString().endsWith("/config") ? rawConfiguration : mismatchedMenu)),
      ),
    );

    await expect(getPublicPageData()).rejects.toBeInstanceOf(PublicApiConsistencyError);
  });
});
