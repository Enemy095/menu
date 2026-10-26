import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ configuration: vi.fn(), menu: vi.fn() }));
vi.mock("@/shared/api/public-api", () => ({
  getPublicConfiguration: api.configuration, getPublicMenu: api.menu,
}));
import { GET } from "./route";

const key = `assets/logo/${"a".repeat(64)}.png`;
const context = (path = key) => ({ params: Promise.resolve({ path: path.split("/") }) });

describe("published asset proxy", () => {
  beforeEach(() => {
    vi.stubEnv("ASSET_PROXY_BASE_URL", "http://minio:9000/menu-assets-local");
    api.configuration.mockResolvedValue({ branding: { logoUrl: `https://public.test/media/${key}` } });
    api.menu.mockResolvedValue({ categories: [], items: [] });
  });

  it("streams only the published image from the configured internal origin", async () => {
    const fetcher = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("image", { headers: { "Content-Type": "image/png" } }));
    const response = await GET(new Request("https://public.test"), context());
    expect(await response.text()).toBe("image");
    expect(response.headers.get("cache-control")).toContain("immutable");
    expect(fetcher).toHaveBeenCalledWith(`http://minio:9000/menu-assets-local/${key}`, expect.objectContaining({ redirect: "error" }));
  });

  it.each(["../secret", "assets/logo/unknown.png", `assets/logo/${"b".repeat(64)}.png`])("rejects unpublished or malformed key %s", async (path) => {
    const fetcher = vi.spyOn(globalThis, "fetch");
    expect((await GET(new Request("https://public.test"), context(path))).status).toBe(404);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("does not expose origin failures", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("private origin error"));
    const response = await GET(new Request("https://public.test"), context());
    expect(response.status).toBe(503);
    expect(await response.text()).toBe("");
  });

  it("rejects non-image content from the origin", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("secret", { headers: { "Content-Type": "text/plain" } }));
    expect((await GET(new Request("https://public.test"), context())).status).toBe(404);
  });
});
