import { publicApiOrigin } from "@/shared/api/public-api";

export const dynamic = "force-dynamic";
export async function GET(): Promise<Response> {
  try {
    const upstream = await fetch(`${publicApiOrigin()}/api/v1/public/availability`, {
      cache: "no-store", signal: AbortSignal.timeout(8_000), headers: { Accept: "application/json" },
    });
    return new Response(upstream.body, { status: upstream.status, headers: {
      "Content-Type": "application/json", "Cache-Control": "no-store",
    } });
  } catch {
    return Response.json({ code: "DEPENDENCY_UNAVAILABLE", message: "Расписание временно недоступно", details: [], traceId: crypto.randomUUID() },
      { status: 503, headers: { "Cache-Control": "no-store" } });
  }
}
