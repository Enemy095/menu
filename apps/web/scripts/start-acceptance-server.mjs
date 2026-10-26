import { spawn } from "node:child_process";
import { join } from "node:path";

const root = process.cwd();
const hostname = process.env.HOSTNAME ?? "0.0.0.0";
const port = process.env.PORT ?? "3201";
const nextCli = join(root, "node_modules", "next", "dist", "bin", "next");
const server = spawn(
  process.execPath,
  [nextCli, "start", root, "--hostname", hostname, "--port", port],
  { cwd: root, env: process.env, stdio: "inherit" },
);

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.kill(signal));
}

const exitCode = await new Promise((resolve) => server.once("exit", resolve));
process.exitCode = typeof exitCode === "number" ? exitCode : 1;
