import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const argumentsSet = new Set(process.argv.slice(2));
const supportedArguments = new Set(["--skip-install", "--skip-smoke"]);
const unknownArguments = [...argumentsSet].filter((argument) => !supportedArguments.has(argument));

if (unknownArguments.length > 0) {
  console.error(`Unsupported validation arguments: ${unknownArguments.join(", ")}`);
  process.exit(2);
}

const stages = [];
if (!argumentsSet.has("--skip-install")) {
  stages.push({
    name: "Frozen pnpm workspace install",
    command: "pnpm",
    arguments: ["install", "--frozen-lockfile"],
  });
}
stages.push(
  {
    name: "Single-client runtime architecture guard",
    command: "node",
    arguments: ["scripts/check-forbidden-runtime-scope.mjs"],
  },
  {
    name: "OpenAPI contract and generated client",
    command: "pnpm",
    arguments: ["--filter", "@e-menu/api-contract", "validate"],
  },
  {
    name: "Contract package build",
    command: "pnpm",
    arguments: ["--filter", "@e-menu/api-contract", "build"],
  },
  {
    name: "Frontend lint, types, unit/accessibility, production build and status integration",
    command: "pnpm",
    arguments: ["--filter", "web", "check"],
  },
  {
    name: "Backend tests, module verification and executable jar",
    command: process.platform === "win32" ? "gradlew.bat" : "./gradlew",
    arguments: ["--no-daemon", ":apps:api:check", ":apps:api:bootJar"],
  },
);
if (!argumentsSet.has("--skip-smoke")) {
  stages.push({
    name: "Executable jar smoke against disposable PostgreSQL",
    command: "node",
    arguments: ["scripts/smoke-disposable-postgres.mjs"],
  });
}
stages.push({
  name: "Patch whitespace validation",
  command: "git",
  arguments: ["diff", "--check"],
});

const startedAt = Date.now();
for (let index = 0; index < stages.length; index += 1) {
  const stage = stages[index];
  console.log(`\n[${index + 1}/${stages.length}] ${stage.name}`);
  const result = run(stage.command, stage.arguments);
  if (result.status !== 0) {
    console.error(`Validation stopped at stage: ${stage.name}`);
    process.exit(result.status ?? 1);
  }
}

console.log(`\nFull validation foundation passed in ${formatDuration(Date.now() - startedAt)}.`);

function run(command, commandArguments) {
  const needsWindowsCommandShell = process.platform === "win32"
    && (command === "pnpm" || command.endsWith(".bat") || command.endsWith(".cmd"));
  if (!needsWindowsCommandShell) {
    return spawnSync(command, commandArguments, {
      cwd: repositoryRoot,
      env: process.env,
      stdio: "inherit",
    });
  }
  const commandLine = [command, ...commandArguments].map(quoteWindowsArgument).join(" ");
  return spawnSync(process.env.ComSpec ?? "cmd.exe", ["/d", "/s", "/c", commandLine], {
    cwd: repositoryRoot,
    env: process.env,
    stdio: "inherit",
  });
}

function quoteWindowsArgument(value) {
  return /[\s"]/u.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
}

function formatDuration(milliseconds) {
  const seconds = Math.round(milliseconds / 1000);
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
