import { openSync, readFileSync } from "node:fs";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";

const scriptsDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptsDir, "..");
const secretFile = join(root, ".env.telegram.local");
const runtimeEnvironment = { ...process.env };

for (const rawLine of readFileSync(secretFile, "utf8").split(/\r?\n/u)) {
  const line = rawLine.trim();
  if (!line || line.startsWith("#")) {
    continue;
  }
  const separator = line.indexOf("=");
  if (separator < 1) {
    throw new Error("Invalid Telegram environment line");
  }
  runtimeEnvironment[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
}

if (
  !runtimeEnvironment.TELEGRAM_BOT_TOKEN ||
  !runtimeEnvironment.TELEGRAM_CHAT_ID
) {
  throw new Error("Run setup-telegram-local.ps1 before launching the API");
}
runtimeEnvironment.SPRING_PROFILES_ACTIVE = "local";
runtimeEnvironment.TELEGRAM_NOTIFICATIONS_ENABLED = "true";
runtimeEnvironment.APP_WORKER_ENABLED = "true";

const stdout = openSync(join(root, ".telegram-api.out.log"), "a");
const stderr = openSync(join(root, ".telegram-api.err.log"), "a");

const child = spawn(
  process.env.ComSpec ?? "cmd.exe",
  [
    "/d",
    "/c",
    "call",
    "gradlew.bat",
    ":apps:api:bootRun",
  ],
  {
    cwd: root,
    detached: true,
    env: runtimeEnvironment,
    windowsHide: true,
    stdio: ["ignore", stdout, stderr],
  },
);

child.unref();
process.stdout.write(`${child.pid}\n`);
