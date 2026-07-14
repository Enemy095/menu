import { access, readFile, readdir } from "node:fs/promises";
import { constants } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const runtimeRoots = [
  "apps/api/src/main",
  "apps/web",
  "packages/api-contract/openapi",
  "infra",
];
const runtimeFiles = [".env.example", "compose.yaml", "package.json"];
const ignoredDirectories = new Set([
  ".next",
  "build",
  "coverage",
  "dist",
  "node_modules",
]);
const textExtensions = new Set([
  ".conf",
  ".css",
  ".env",
  ".example",
  ".html",
  ".java",
  ".js",
  ".json",
  ".jsx",
  ".kts",
  ".mjs",
  ".properties",
  ".sh",
  ".sql",
  ".toml",
  ".ts",
  ".tsx",
  ".txt",
  ".xml",
  ".yaml",
  ".yml",
]);
const forbidden = /tenant/i;
const frozenHistoricalMigration = /^apps\/api\/src\/main\/resources\/db\/migration\/V[1-7]__/u;

const allowedDefensiveMatches = new Map([
  [
    "apps/api/src/main/java/com/salamu/menu/ObservabilityConfiguration.java",
    new Set([
      '"tenantslug",',
      '"tenantid");',
      'private static final Set<String> FORBIDDEN_METRIC_TAG_KEYS = Set.of("tenant", "user");',
    ]),
  ],
]);

const files = [];
for (const root of runtimeRoots) {
  await requirePath(root);
  await collect(root);
}
for (const file of runtimeFiles) {
  await requirePath(file);
  files.push(file);
}

const violations = [];
let allowedMatchCount = 0;
let scannedFileCount = 0;
let frozenMigrationCount = 0;
for (const relativeFile of [...new Set(files)].sort()) {
  if (frozenHistoricalMigration.test(relativeFile)) {
    frozenMigrationCount += 1;
    continue;
  }
  scannedFileCount += 1;
  if (forbidden.test(relativeFile)) {
    violations.push(`${relativeFile}: forbidden runtime scope appears in the path`);
  }

  const content = await readFile(path.join(repositoryRoot, relativeFile), "utf8");
  const allowedLines = allowedDefensiveMatches.get(relativeFile) ?? new Set();
  content.split(/\r?\n/u).forEach((line, index) => {
    if (!forbidden.test(line)) {
      return;
    }
    if (allowedLines.has(line.trim())) {
      allowedMatchCount += 1;
      return;
    }
    violations.push(`${relativeFile}:${index + 1}: ${line.trim()}`);
  });
}

const expectedAllowedMatches = [...allowedDefensiveMatches.values()]
  .reduce((total, lines) => total + lines.size, 0);
if (allowedMatchCount !== expectedAllowedMatches) {
  violations.push(
    `defensive allowlist drift: expected ${expectedAllowedMatches} exact matches, found ${allowedMatchCount}`,
  );
}

if (violations.length > 0) {
  console.error("Forbidden runtime tenant scope detected:");
  violations.forEach((violation) => console.error(`- ${violation}`));
  process.exit(1);
}

console.log(
  `Runtime scope guard passed: ${scannedFileCount} relevant runtime text files scanned; `
    + `${allowedMatchCount} exact observability deny-list literals allowed; `
    + `${frozenMigrationCount} frozen V1-V7 migrations skipped.`,
);

async function requirePath(relativePath) {
  try {
    await access(path.join(repositoryRoot, relativePath), constants.R_OK);
  } catch {
    throw new Error(`Required runtime scan path is missing: ${relativePath}`);
  }
}

async function collect(relativeDirectory) {
  const absoluteDirectory = path.join(repositoryRoot, relativeDirectory);
  for (const entry of await readdir(absoluteDirectory, { withFileTypes: true })) {
    if (entry.isDirectory() && ignoredDirectories.has(entry.name)) {
      continue;
    }
    const relativeEntry = path.posix.join(relativeDirectory.replaceAll("\\", "/"), entry.name);
    if (entry.isDirectory()) {
      await collect(relativeEntry);
      continue;
    }
    if (!entry.isFile()) {
      continue;
    }
    const extension = path.extname(entry.name).toLowerCase();
    if (textExtensions.has(extension) || entry.name === "Dockerfile") {
      files.push(relativeEntry);
    }
  }
}
