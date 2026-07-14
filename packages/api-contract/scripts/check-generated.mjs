import { mkdtemp, rm } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import path from "node:path";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const expected = path.join(packageRoot, "generated/typescript-fetch");
const temporaryRoot = await mkdtemp(path.join(tmpdir(), "e-menu-api-client-"));
const actual = path.join(temporaryRoot, "typescript-fetch");

try {
  const generate = spawnSync(process.execPath, [path.join(packageRoot, "scripts/generate-client.mjs"), actual], {
    cwd: packageRoot,
    stdio: "inherit",
  });

  if (generate.error) {
    throw generate.error;
  }
  if (generate.status !== 0) {
    process.exit(generate.status ?? 1);
  }

  const diffCommand = process.platform === "win32" ? "git.exe" : "git";
  const diff = spawnSync(diffCommand, ["diff", "--no-index", "--exit-code", "--", expected, actual], {
    cwd: packageRoot,
    encoding: "utf8",
  });

  if (diff.status === 0) {
    console.log("Generated TypeScript client is up to date and deterministic.");
  } else if (diff.status === 1) {
    process.stderr.write(diff.stdout);
    process.stderr.write(diff.stderr);
    console.error("Generated TypeScript client differs. Run `pnpm generate` and commit the result.");
    process.exit(1);
  } else {
    process.stderr.write(diff.stdout);
    process.stderr.write(diff.stderr);
    process.exit(diff.status ?? 1);
  }
} finally {
  await rm(temporaryRoot, { recursive: true, force: true });
}
