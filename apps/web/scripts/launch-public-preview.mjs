import { spawn } from "node:child_process";
import {
  cpSync,
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";

const root = process.cwd();
const port = 3202;
const runtimeRoot = join(root, ".runtime", "public-preview");
const stateFile = join(runtimeRoot, "state.json");
const releaseRoot = join(runtimeRoot, `release-${Date.now()}`);
const nextCli = join(root, "node_modules", "next", "dist", "bin", "next");

function processIsAlive(pid) {
  if (!Number.isInteger(pid) || pid < 1) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function stopManagedProcess(pid) {
  if (!processIsAlive(pid)) return;
  process.kill(pid, "SIGTERM");
  for (let attempt = 0; attempt < 40 && processIsAlive(pid); attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  if (processIsAlive(pid)) process.kill(pid, "SIGKILL");
}

async function waitFor(check, description, attempts = 80) {
  let lastError;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const result = await check();
      if (result) return result;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(
    `${description} did not become ready${lastError ? `: ${String(lastError)}` : ""}`,
  );
}

if (!existsSync(join(root, ".next", "BUILD_ID"))) {
  throw new Error("Production build is missing. Run `pnpm build` first.");
}

mkdirSync(runtimeRoot, { recursive: true });
cpSync(join(root, ".next"), join(releaseRoot, ".next"), {
  recursive: true,
  force: true,
});
cpSync(join(root, "public"), join(releaseRoot, "public"), {
  recursive: true,
  force: true,
});
cpSync(join(root, "package.json"), join(releaseRoot, "package.json"));

let previous = {};
if (existsSync(stateFile)) {
  try {
    previous = JSON.parse(readFileSync(stateFile, "utf8"));
  } catch {
    previous = {};
  }
}

await stopManagedProcess(previous.serverPid);

const serverLog = openSync(join(runtimeRoot, "server.log"), "w");
const server = spawn(
  process.execPath,
  [nextCli, "start", releaseRoot, "--hostname", "0.0.0.0", "--port", String(port)],
  {
  cwd: root,
  detached: true,
  windowsHide: true,
  stdio: ["ignore", serverLog, serverLog],
  env: {
    ...process.env,
    HOSTNAME: "0.0.0.0",
    MENU_API_BASE_URL: process.env.MENU_API_BASE_URL ?? "http://127.0.0.1:8080",
    PORT: String(port),
  },
  },
);
closeSync(serverLog);
server.unref();

await waitFor(async () => {
  const response = await fetch(`http://127.0.0.1:${port}/`, { redirect: "manual" });
  return response.status === 200;
}, "Public preview server");

let ngrokPid = previous.ngrokPid;
let tunnel = null;

if (processIsAlive(ngrokPid)) {
  try {
    const response = await fetch("http://127.0.0.1:4040/api/tunnels");
    const payload = response.ok ? await response.json() : {};
    tunnel = payload.tunnels?.find(
      (candidate) =>
        candidate.proto === "https" &&
        String(candidate.config?.addr ?? "").endsWith(`:${port}`),
    );
  } catch {
    tunnel = null;
  }
}

if (!tunnel) {
  await stopManagedProcess(ngrokPid);
  const ngrokLog = openSync(join(runtimeRoot, "ngrok.log"), "w");
  const ngrok = spawn("ngrok", ["http", String(port)], {
    cwd: root,
    detached: true,
    windowsHide: true,
    stdio: ["ignore", ngrokLog, ngrokLog],
  });
  closeSync(ngrokLog);
  ngrok.unref();
  ngrokPid = ngrok.pid;

  tunnel = await waitFor(async () => {
    const response = await fetch("http://127.0.0.1:4040/api/tunnels");
    if (!response.ok) return null;
    const payload = await response.json();
    return payload.tunnels?.find(
      (candidate) =>
        candidate.proto === "https" &&
        String(candidate.config?.addr ?? "").endsWith(`:${port}`),
    );
  }, "ngrok tunnel");
}

const publicResponse = await waitFor(async () => {
  const response = await fetch(tunnel.public_url, {
    headers: { "ngrok-skip-browser-warning": "1" },
  });
  return response.status === 200 ? response : null;
}, "Public ngrok URL");

writeFileSync(
  stateFile,
  JSON.stringify(
    {
      ngrokPid,
      publicUrl: tunnel.public_url,
      releaseRoot,
      serverPid: server.pid,
      startedAt: new Date().toISOString(),
    },
    null,
    2,
  ),
);

if (previous.releaseRoot && previous.releaseRoot !== releaseRoot) {
  try {
    rmSync(previous.releaseRoot, { recursive: true, force: true });
  } catch {
    // A stale release is harmless and remains ignored under .runtime.
  }
}

console.log(`Public preview ready: ${tunnel.public_url}`);
console.log(`Local preview: http://127.0.0.1:${port}`);
console.log(`HTTP status: ${publicResponse.status}`);
