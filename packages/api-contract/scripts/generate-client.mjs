import { readFile, readdir, rm, mkdir, writeFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const output = path.resolve(process.argv[2] ?? path.join(packageRoot, "generated/typescript-fetch"));
const cli = path.join(packageRoot, "node_modules", "@openapitools", "openapi-generator-cli", "main.js");

await rm(output, { recursive: true, force: true });
await mkdir(path.dirname(output), { recursive: true });

const result = spawnSync(
  process.execPath,
  [
    cli,
    "generate",
    "--generator-name",
    "typescript-fetch",
    "--input-spec",
    path.join(packageRoot, "openapi/openapi.yaml"),
    "--config",
    path.join(packageRoot, "openapi-generator.config.yaml"),
    "--output",
    output,
  ],
  { cwd: packageRoot, stdio: "inherit" },
);

if (result.error) {
  throw result.error;
}

if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

async function normalizeGeneratedText(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      await normalizeGeneratedText(entryPath);
      continue;
    }

    const original = await readFile(entryPath, "utf8");
    const normalized = original
      .replace(/[ \t]+(?=\r?$)/gm, "")
      .replace(/(?:\r?\n)+$/, "\n");
    if (normalized !== original) {
      await writeFile(entryPath, normalized, "utf8");
    }
  }
}

await normalizeGeneratedText(output);
