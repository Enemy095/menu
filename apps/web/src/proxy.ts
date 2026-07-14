import { NextResponse } from "next/server";

import { isPublicApiUnavailable, probePublicApiReadiness } from "@/shared/api/public-api";

const SERVICE_UNAVAILABLE_DOCUMENT = `<!doctype html>
<html lang="ru-RU">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow">
    <title>Электронное меню временно недоступно</title>
  </head>
  <body>
    <main>
      <p>Электронное меню</p>
      <h1>Меню временно недоступно</h1>
      <p>Сервис обновляется. Пожалуйста, попробуйте открыть страницу позже.</p>
    </main>
  </body>
</html>`;

export async function proxy(): Promise<NextResponse> {
  try {
    await probePublicApiReadiness();
    return NextResponse.next();
  } catch (error) {
    if (!isPublicApiUnavailable(error)) return NextResponse.next();

    return new NextResponse(SERVICE_UNAVAILABLE_DOCUMENT, {
      status: 503,
      headers: {
        "Cache-Control": "no-store",
        "Content-Type": "text/html; charset=utf-8",
        "X-Robots-Tag": "noindex, nofollow",
      },
    });
  }
}

export const config = { matcher: "/" };
