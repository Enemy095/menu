import { getPublicConfiguration, getPublicMenu } from "@/shared/api/public-api";

export const dynamic = "force-dynamic";

// Only published, content-addressed images can be fetched from the private origin.
export async function GET(
  _request: Request,
  context: { params: Promise<{ path: string[] }> },
): Promise<Response> {
  const { path } = await context.params;
  const key = path.join("/");
  if (!/^assets\/[a-z-]+\/[a-f0-9]{64}\.(png|jpg|jpeg|webp|ico|svg)$/.test(key)) {
    return new Response(null, { status: 404 });
  }
  try {
    const base = process.env.ASSET_PROXY_BASE_URL;
    if (!base) return new Response(null, { status: 503 });
    const [configuration, menu] = await Promise.all([getPublicConfiguration(), getPublicMenu()]);
    const published = [
      configuration.branding.logoUrl,
      configuration.branding.faviconUrl,
      configuration.branding.hero?.imageUrl,
      configuration.seo?.openGraphImageUrl,
      ...menu.categories.map((category) => category.imageUrl),
      ...menu.items.map((item) => item.imageUrl),
    ];
    if (!published.some((url) => url && new URL(url).pathname === `/media/${key}`)) {
      return new Response(null, { status: 404 });
    }
    const upstream = await fetch(`${base.replace(/\/$/, "")}/${key}`, {
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(8_000),
    });
    if (!upstream.ok) return new Response(null, { status: 404 });
    const type = upstream.headers.get("content-type")?.split(";")[0];
    if (!type || !["image/png", "image/jpeg", "image/webp", "image/x-icon", "image/vnd.microsoft.icon", "image/svg+xml"].includes(type)) {
      return new Response(null, { status: 404 });
    }
    return new Response(upstream.body, { headers: {
      "Content-Type": type,
      "Cache-Control": "public, max-age=31536000, immutable",
      "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy": "default-src 'none'; sandbox",
    } });
  } catch {
    return new Response(null, { status: 503 });
  }
}
