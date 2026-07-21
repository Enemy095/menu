import { spawn } from "node:child_process";
import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";

const require = createRequire(import.meta.url);
const root = process.cwd();
const runtimeRoot = join(root, ".runtime", "dev");
const stateFile = join(runtimeRoot, "state.json");

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

async function waitForDevServer() {
  let lastError;
  for (let attempt = 0; attempt < 120; attempt += 1) {
    try {
      const response = await fetch("http://127.0.0.1:3000/", { redirect: "manual" });
      if (response.status === 200) return response.status;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Dev server did not become ready: ${String(lastError ?? "timeout")}`);
}

mkdirSync(runtimeRoot, { recursive: true });
let previous = {};
if (existsSync(stateFile)) {
  try {
    previous = JSON.parse(readFileSync(stateFile, "utf8"));
  } catch {
    previous = {};
  }
}
await stopManagedProcess(previous.pid);

const log = openSync(join(runtimeRoot, "dev.log"), "w");
const nextBin = require.resolve("next/dist/bin/next");
const child = spawn(
  process.execPath,
  [nextBin, "dev", "--hostname", "0.0.0.0", "--port", "3000"],
  {
    cwd: root,
    detached: true,
    windowsHide: true,
    stdio: ["ignore", log, log],
    env: {
      ...process.env,
      MENU_API_BASE_URL: process.env.MENU_API_BASE_URL ?? "http://127.0.0.1:8080",
    },
  },
);
closeSync(log);
child.unref();

const status = await waitForDevServer();
writeFileSync(
  stateFile,
  JSON.stringify({ pid: child.pid, startedAt: new Date().toISOString() }, null, 2),
);

console.log(`Dev server ready: http://localhost:3000 (HTTP ${status})`);
