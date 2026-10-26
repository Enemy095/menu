import { NextResponse } from "next/server";

import { publicApiOrigin } from "@/shared/api/public-api";

const IDEMPOTENCY_KEY = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const MAX_ORDER_BODY_BYTES = 64 * 1024;

function error(status: number, code: string, message: string): NextResponse {
  return NextResponse.json(
    { code, message, details: [], traceId: crypto.randomUUID() },
    { status, headers: { "Cache-Control": "no-store" } },
  );
}

function declaredLength(request: Request): number | null {
  const header = request.headers.get("content-length");
  if (header === null) return null;
  if (!/^\d+$/u.test(header)) return Number.NaN;
  return Number(header);
}

async function readBoundedBody(request: Request): Promise<string | null> {
  if (!request.body) return "";
  const reader = request.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let result = "";
  let total = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      total += chunk.value.byteLength;
      if (total > MAX_ORDER_BODY_BYTES) {
        await reader.cancel("request body limit exceeded");
        return null;
      }
      result += decoder.decode(chunk.value, { stream: true });
    }
    result += decoder.decode();
    return result;
  } catch {
    await reader.cancel("invalid request body").catch(() => undefined);
    return null;
  }
}

export async function POST(request: Request): Promise<Response> {
  const idempotencyKey = request.headers.get("Idempotency-Key")?.trim();
  if (!idempotencyKey || !IDEMPOTENCY_KEY.test(idempotencyKey)) {
    return error(400, "VALIDATION_ERROR", "Некорректный Idempotency-Key");
  }
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) {
    return error(415, "VALIDATION_ERROR", "Ожидается application/json");
  }

  const contentLength = declaredLength(request);
  if (Number.isNaN(contentLength) || (contentLength !== null && contentLength > MAX_ORDER_BODY_BYTES)) {
    return error(413, "VALIDATION_ERROR", "Некорректный размер запроса");
  }
  const body = await readBoundedBody(request);
  if (!body) {
    return error(413, "VALIDATION_ERROR", "Некорректный размер запроса");
  }

  try {
    const upstream = await fetch(`${publicApiOrigin()}/api/v1/public/orders`, {
      method: "POST",
      cache: "no-store",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
      },
      body,
    });
    const headers = new Headers({
      "Cache-Control": "no-store",
      "Content-Type": upstream.headers.get("content-type") ?? "application/json; charset=utf-8",
    });
    for (const name of ["Idempotency-Replayed", "Retry-After", "X-Trace-Id"]) {
      const value = upstream.headers.get(name);
      if (value) headers.set(name, value);
    }
    return new Response(upstream.body, { status: upstream.status, headers });
  } catch {
    return error(503, "DEPENDENCY_UNAVAILABLE", "Сервис заказов временно недоступен");
  }
}
