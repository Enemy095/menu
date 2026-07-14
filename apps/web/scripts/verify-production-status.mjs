import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";

import { rawConfiguration, rawMenu } from "../src/test/raw-public-data.ts";

const host = "127.0.0.1";

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, host, () => resolve(server.address().port));
  });
}

async function unusedPort() {
  const server = createServer();
  const port = await listen(server);
  await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
  return port;
}

let upstreamMode = "healthy";
const upstreamRequests = { readiness: 0, content: 0 };

const upstream = createServer((request, response) => {
  const isReadiness = request.headers["x-menu-readiness"] === "1";
  upstreamRequests[isReadiness ? "readiness" : "content"] += 1;

  if (upstreamMode === "outage") {
    response.writeHead(503, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ code: "DEPENDENCY_UNAVAILABLE", path: request.url }));
    return;
  }

  const payload = request.url === "/api/v1/public/config" ? rawConfiguration : rawMenu;
  response.writeHead(200, { "Content-Type": "application/json" });
  response.end(JSON.stringify(payload));
});
const upstreamPort = await listen(upstream);
const webPort = await unusedPort();
const nextBin = fileURLToPath(new URL("../node_modules/next/dist/bin/next", import.meta.url));
const web = spawn(process.execPath, [nextBin, "start", "--hostname", host, "--port", String(webPort)], {
  cwd: fileURLToPath(new URL("..", import.meta.url)),
  env: {
    ...process.env,
    NODE_ENV: "production",
    MENU_API_BASE_URL: `http://${host}:${upstreamPort}`,
  },
  stdio: ["ignore", "pipe", "pipe"],
});
const webExit = new Promise((resolve) => web.once("exit", resolve));

let diagnostics = "";
web.stdout.on("data", (chunk) => (diagnostics += chunk.toString()));
web.stderr.on("data", (chunk) => (diagnostics += chunk.toString()));

try {
  const deadline = Date.now() + 30_000;
  let firstHealthyResponse;
  while (Date.now() < deadline) {
    try {
      firstHealthyResponse = await fetch(`http://${host}:${webPort}/`, { signal: AbortSignal.timeout(5_000) });
      break;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }
  assert.ok(firstHealthyResponse, `Next server did not start in time:\n${diagnostics}`);
  const firstHealthyBody = await firstHealthyResponse.text();
  assert.equal(firstHealthyResponse.status, 200, firstHealthyBody);
  assert.match(firstHealthyBody, /Тестовое кафе/u);
  assert.equal(upstreamRequests.readiness, 2);
  const contentRequestsAfterFirstRender = upstreamRequests.content;
  console.log("Initial healthy render passed.");

  const warmHealthyResponse = await fetch(`http://${host}:${webPort}/`, {
    signal: AbortSignal.timeout(10_000),
  });
  const warmHealthyBody = await warmHealthyResponse.text();
  assert.equal(warmHealthyResponse.status, 200, warmHealthyBody);
  assert.match(warmHealthyBody, /Тестовое кафе/u);
  assert.equal(upstreamRequests.readiness, 4, "every request must bypass cache for readiness");
  assert.equal(
    upstreamRequests.content,
    contentRequestsAfterFirstRender,
    "warm ISR/data cache must avoid a second content fetch",
  );
  assert.match(warmHealthyResponse.headers.get("cache-control") ?? "", /s-maxage/u);
  console.log("Warm healthy ISR cache passed.");

  upstreamMode = "outage";
  const outageResponse = await fetch(`http://${host}:${webPort}/`, { signal: AbortSignal.timeout(10_000) });
  const outageBody = await outageResponse.text();
  assert.equal(outageResponse.status, 503, outageBody);
  assert.equal(upstreamRequests.readiness, 6, "outage must be observed through fresh readiness probes");
  assert.equal(upstreamRequests.content, contentRequestsAfterFirstRender);
  assert.match(outageResponse.headers.get("x-robots-tag") ?? "", /noindex/u);
  assert.match(outageResponse.headers.get("cache-control") ?? "", /no-store/u);
  assert.match(outageBody, /<meta name="robots" content="noindex, nofollow">/u);
  assert.match(outageBody, /Меню временно недоступно/u);
  console.log("Production integration proves healthy ISR caching and warm-cache outage HTTP 503.");
} finally {
  web.kill();
  await Promise.race([webExit, new Promise((resolve) => setTimeout(resolve, 3_000))]);
  if (web.exitCode === null) {
    web.kill("SIGKILL");
    await webExit;
  }
  upstream.closeAllConnections();
  await new Promise((resolve) => upstream.close(() => resolve()));
}
