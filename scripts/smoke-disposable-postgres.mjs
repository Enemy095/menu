import { spawn, spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { access } from "node:fs/promises";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import path from "node:path";
import process from "node:process";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const jarPath = path.join(repositoryRoot, "apps", "api", "build", "libs", "menu-platform-api.jar");
const postgresImage = "postgres:17.5-alpine3.21";
const disposableRunId = randomUUID();
const disposableRunLabel = "com.salamu.validation.run-id";
const containerName = `emenu-validation-pg-${process.pid}-${disposableRunId.slice(0, 8)}`;
const databaseName = "validation";
const databaseUser = "validation";
const databasePassword = `validation-${randomUUID()}`;
const defaultSyncTimeoutMilliseconds = 30_000;
const expectedApplicationTables = [
  "site_configuration",
  "assets",
  "site_branding",
  "site_profile",
  "site_features",
  "restaurants",
  "order_counter",
  "menu_versions",
  "menu_categories",
  "menu_items",
  "orders",
  "order_items",
  "order_status_history",
  "notification_destinations",
  "outbox_events",
  "notification_deliveries",
  "menu_modifier_groups",
  "menu_modifier_options",
];
const flywayMetadataTable = "flyway_schema_history";
const capturedApplicationOutput = [];
const validationAbort = new AbortController();
const signalHandlers = new Map();
let applicationProcess;
let applicationSpawnError;
let dockerRunAttempted = false;
let cleanupPromise;
let receivedSignal;
let smokeAssertionsPassed = false;

installSignalHandlers();
let failure;
try {
  await runSmoke();
} catch (error) {
  failure = error;
} finally {
  try {
    await cleanup();
  } catch (cleanupError) {
    failure = failure
      ? new AggregateError([failure, cleanupError], "Validation and cleanup both failed")
      : cleanupError;
  }
  removeSignalHandlers();
}

if (failure) {
  console.error(`Disposable smoke failed: ${formatError(failure)}`);
  if (capturedApplicationOutput.length > 0) {
    console.error("Last application output:");
    console.error(capturedApplicationOutput.slice(-80).join("\n"));
  }
  process.exitCode = process.exitCode || 1;
} else if (smokeAssertionsPassed) {
  console.log(
    "Disposable smoke passed: V9 schema, healthy probes, fail-closed public reads, "
      + "exact public schema and every discovered application table empty, process/container cleanup proven.",
  );
}

async function runSmoke() {
  await access(jarPath);
  const { environment, scrubbedNames } = scrubApplicationEnvironment(process.env);
  runChecked("docker", ["version", "--format", "{{.Server.Version}}"], "Docker daemon check", {
    timeoutMilliseconds: 10_000,
  });
  runChecked("java", ["-version"], "Java runtime check", {
    quiet: true,
    env: environment,
    timeoutMilliseconds: 10_000,
  });
  validationAbort.signal.throwIfAborted();

  const databasePort = await reserveFreePort();
  const applicationPort = await reserveFreePort();
  validationAbort.signal.throwIfAborted();
  dockerRunAttempted = true;
  const containerId = runChecked(
    "docker",
    [
      "run",
      "--detach",
      "--rm",
      "--name",
      containerName,
      "--label",
      "com.salamu.validation.disposable=true",
      "--label",
      `${disposableRunLabel}=${disposableRunId}`,
      "--env",
      `POSTGRES_DB=${databaseName}`,
      "--env",
      `POSTGRES_USER=${databaseUser}`,
      "--env",
      `POSTGRES_PASSWORD=${databasePassword}`,
      "--publish",
      `127.0.0.1:${databasePort}:5432`,
      "--tmpfs",
      "/var/lib/postgresql/data:rw,noexec,nosuid,size=512m",
      postgresImage,
    ],
    "Disposable PostgreSQL startup",
    { timeoutMilliseconds: 120_000 },
  );
  assertDisposableContainer(databasePort);
  console.log(
    `Disposable PostgreSQL started: ${containerId.slice(0, 12)}; `
      + `${scrubbedNames.length} contaminating environment variables removed from Java.`,
  );

  await waitForDatabase();
  applicationProcess = startApplication(environment, databasePort, applicationPort);
  const readiness = await waitForReadiness(applicationPort);
  assert(readiness.status === 200, `readiness returned HTTP ${readiness.status}`);
  assert(readiness.body.status === "UP", `readiness status is ${JSON.stringify(readiness.body.status)}`);

  const liveness = await requestJson(applicationPort, "/actuator/health/liveness");
  assert(liveness.status === 200, `liveness returned HTTP ${liveness.status}`);
  assert(liveness.body.status === "UP", `liveness status is ${JSON.stringify(liveness.body.status)}`);

  await assertSiteNotReady(applicationPort, "/api/v1/public/config");
  await assertSiteNotReady(applicationPort, "/api/v1/public/menu");
  assertDatabaseState();

  smokeAssertionsPassed = true;
}

function scrubApplicationEnvironment(source) {
  const environment = {};
  const scrubbedNames = [];
  for (const [name, value] of Object.entries(source)) {
    if (isContaminatingEnvironmentName(name)) {
      scrubbedNames.push(name);
    } else if (value !== undefined) {
      environment[name] = value;
    }
  }
  const leaked = Object.keys(environment).filter(isContaminatingEnvironmentName);
  assert(leaked.length === 0, `contaminating environment was not scrubbed: ${leaked.join(", ")}`);
  return { environment, scrubbedNames: scrubbedNames.sort() };
}

function isContaminatingEnvironmentName(name) {
  return /^(?:SPRING_|SERVER_|MANAGEMENT_|DATABASE_|APP_|ASSET_|OTEL_|LOGGING_)/iu.test(name)
    || /^(?:PUBLIC_BASE_URL|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|JAVA_OPTS|CLASSPATH)$/iu.test(name);
}

function startApplication(environment, databasePort, applicationPort) {
  const argumentsList = [
    "-jar",
    jarPath,
    "--spring.datasource.url="
      + `jdbc:postgresql://127.0.0.1:${databasePort}/${databaseName}`,
    `--spring.datasource.username=${databaseUser}`,
    `--spring.datasource.password=${databasePassword}`,
    "--spring.flyway.enabled=true",
    "--spring.jpa.open-in-view=false",
    "--spring.jpa.hibernate.ddl-auto=validate",
    "--server.address=127.0.0.1",
    `--server.port=${applicationPort}`,
    "--app.security.request-authority.allowed-hosts=127.0.0.1",
    `--app.security.cors.allowed-origins=http://127.0.0.1:${applicationPort}`,
    "--app.security.trusted-proxies.enabled=false",
    "--app.assets.storage.endpoint=http://127.0.0.1:1",
    "--app.assets.storage.region=us-east-1",
    "--app.assets.storage.bucket=validation-assets",
    "--app.assets.storage.access-key=validation-local-access",
    "--app.assets.storage.secret-key=validation-local-secret",
    "--app.assets.storage.path-style-access=true",
    "--app.assets.cdn-base-url=https://assets.invalid",
    `--app.external.public-base-url=http://127.0.0.1:${applicationPort}`,
    "--app.external.asset-base-url=https://assets.invalid",
    "--app.worker.enabled=false",
    "--management.tracing.export.otlp.enabled=false",
    "--management.otlp.metrics.export.enabled=false",
    "--management.logging.export.otlp.enabled=false",
  ];
  const child = spawn("java", argumentsList, {
    cwd: repositoryRoot,
    env: environment,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  child.once("error", (error) => {
    applicationSpawnError = error;
    captureApplicationOutput(`Java spawn error: ${error.message}`);
  });
  child.stdout.on("data", captureApplicationOutput);
  child.stderr.on("data", captureApplicationOutput);
  return child;
}

function captureApplicationOutput(chunk) {
  capturedApplicationOutput.push(...String(chunk).split(/\r?\n/u).filter(Boolean));
  if (capturedApplicationOutput.length > 400) {
    capturedApplicationOutput.splice(0, capturedApplicationOutput.length - 400);
  }
}

function assertDisposableContainer(databasePort) {
  const inspected = inspectContainer();
  assert(inspected.Config?.Image === postgresImage, `unexpected database image ${inspected.Config?.Image}`);
  assert(
    inspected.Config?.Labels?.["com.salamu.validation.disposable"] === "true",
    "disposable validation label is missing",
  );
  assert(
    inspected.Config?.Labels?.[disposableRunLabel] === disposableRunId,
    "unique disposable run label is missing",
  );
  assert(Array.isArray(inspected.Mounts) && inspected.Mounts.length === 0, "disposable database has a bind or volume mount");
  const dataTmpfs = inspected.HostConfig?.Tmpfs?.["/var/lib/postgresql/data"];
  assert(
    typeof dataTmpfs === "string"
      && dataTmpfs.includes("rw")
      && /(?:^|,)size=(?:512m|536870912)(?:,|$)/u.test(dataTmpfs),
    `database data tmpfs is missing or unexpected: ${JSON.stringify(dataTmpfs)}`,
  );
  const bindings = inspected.NetworkSettings?.Ports?.["5432/tcp"];
  assert(Array.isArray(bindings) && bindings.length === 1, "database port has an unexpected binding");
  assert(bindings[0].HostIp === "127.0.0.1", `database is not loopback-bound: ${bindings[0].HostIp}`);
  assert(bindings[0].HostPort === String(databasePort), `database port mismatch: ${bindings[0].HostPort}`);
}

function inspectContainer() {
  const output = runChecked("docker", ["inspect", containerName], "Disposable PostgreSQL inspection", {
    timeoutMilliseconds: 10_000,
  });
  const result = JSON.parse(output);
  assert(Array.isArray(result) && result.length === 1, "Docker inspect did not return exactly one container");
  return result[0];
}

async function waitForDatabase() {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    validationAbort.signal.throwIfAborted();
    const result = spawnBounded(
      "docker",
      ["exec", containerName, "pg_isready", "--username", databaseUser, "--dbname", databaseName],
      "PostgreSQL readiness command",
      { timeoutMilliseconds: 5_000 },
    );
    if (result.status === 0) return;
    await abortableDelay(500);
  }
  throw new Error("PostgreSQL did not become ready within 30 seconds");
}

async function waitForReadiness(applicationPort) {
  const deadline = Date.now() + 60_000;
  let lastFailure = "no response";
  while (Date.now() < deadline) {
    validationAbort.signal.throwIfAborted();
    if (applicationSpawnError) throw new Error(`application could not spawn: ${applicationSpawnError.message}`);
    if (applicationProcess?.exitCode !== null) {
      throw new Error(`application exited before readiness with code ${applicationProcess.exitCode}`);
    }
    try {
      const response = await requestJson(applicationPort, "/actuator/health/readiness");
      if (response.status === 200 && response.body.status === "UP") return response;
      lastFailure = `HTTP ${response.status}: ${JSON.stringify(response.body)}`;
    } catch (error) {
      if (validationAbort.signal.aborted) throw error;
      lastFailure = error instanceof Error ? error.message : String(error);
    }
    await abortableDelay(500);
  }
  throw new Error(`application did not become ready within 60 seconds (${lastFailure})`);
}

async function assertSiteNotReady(applicationPort, requestPath) {
  const response = await requestJson(applicationPort, requestPath);
  assert(response.status === 503, `${requestPath} returned HTTP ${response.status}, expected 503`);
  assert(response.body.code === "SITE_NOT_READY", `${requestPath} returned code ${JSON.stringify(response.body.code)}`);
  assert(typeof response.body.traceId === "string" && response.body.traceId.length > 0, `${requestPath} omitted traceId`);
  assert(response.headers.get("cache-control")?.toLowerCase().includes("no-store"), `${requestPath} omitted Cache-Control: no-store`);
}

function assertDatabaseState() {
  const identityOutput = queryDatabase([
    "SELECT 'database=' || current_database() || ',user=' || current_user;",
    "SELECT 'version=' || version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;",
  ].join("\n"), "Disposable database identity assertions");
  const identity = new Set(outputLines(identityOutput));
  for (const assertion of [`database=${databaseName},user=${databaseUser}`, "version=9"]) {
    assert(identity.has(assertion), `database assertion missing ${assertion}; received ${[...identity].join(", ")}`);
  }

  const discoveredTables = outputLines(queryDatabase(
    "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public' ORDER BY tablename;",
    "Public schema table discovery",
  ));
  assert(
    new Set(discoveredTables).size === discoveredTables.length,
    `public schema discovery returned duplicate names: ${discoveredTables.join(", ")}`,
  );
  const discoveredApplicationTables = discoveredTables.filter((table) => table !== flywayMetadataTable);
  const countQuery = discoveredApplicationTables
    .map((table) => `SELECT ${sqlLiteral(`${table}=`)} || count(*) FROM ${sqlIdentifier(table)};`)
    .join("\n");
  const counts = new Map();
  if (countQuery) {
    for (const row of outputLines(queryDatabase(countQuery, "Discovered application table counts"))) {
      const separator = row.lastIndexOf("=");
      assert(separator > 0, `invalid table count row: ${row}`);
      const table = row.slice(0, separator);
      const count = Number(row.slice(separator + 1));
      assert(Number.isSafeInteger(count) && count >= 0, `invalid row count for ${table}: ${row}`);
      counts.set(table, count);
    }
  }
  assert(
    counts.size === discoveredApplicationTables.length,
    `not every discovered application table was counted: discovered=${discoveredApplicationTables.join(",")}; counted=${[...counts.keys()].join(",")}`,
  );

  const expectedPublicTables = new Set([...expectedApplicationTables, flywayMetadataTable]);
  const discoveredTableSet = new Set(discoveredTables);
  const missing = [...expectedPublicTables].filter((table) => !discoveredTableSet.has(table));
  const unexpected = [...discoveredTableSet].filter((table) => !expectedPublicTables.has(table));
  assert(
    missing.length === 0 && unexpected.length === 0,
    `public schema table set mismatch; missing=[${missing.join(",")}]; unexpected=[${unexpected.join(",")}]; counts=${JSON.stringify(Object.fromEntries(counts))}`,
  );
  const nonEmpty = [...counts].filter(([, count]) => count !== 0);
  assert(
    nonEmpty.length === 0,
    `disposable database contains application rows: ${nonEmpty.map(([table, count]) => `${table}=${count}`).join(", ")}`,
  );
}

function queryDatabase(query, label) {
  return runChecked(
    "docker",
    ["exec", containerName, "psql", "--username", databaseUser, "--dbname", databaseName, "--tuples-only", "--no-align", "--command", query],
    label,
    { timeoutMilliseconds: 20_000 },
  );
}

function outputLines(output) {
  return output.split(/\r?\n/u).map((line) => line.trim()).filter(Boolean);
}

function sqlIdentifier(value) {
  return `"${value.replaceAll('"', '""')}"`;
}

function sqlLiteral(value) {
  return `'${value.replaceAll("'", "''")}'`;
}

async function requestJson(port, requestPath) {
  const response = await fetch(`http://127.0.0.1:${port}${requestPath}`, {
    headers: { Accept: "application/json" },
    signal: AbortSignal.any([validationAbort.signal, AbortSignal.timeout(5_000)]),
  });
  const rawBody = await response.text();
  let body;
  try {
    body = JSON.parse(rawBody);
  } catch {
    throw new Error(`${requestPath} did not return JSON: ${rawBody.slice(0, 300)}`);
  }
  return { status: response.status, headers: response.headers, body };
}

function spawnBounded(command, args, label, options = {}) {
  const timeoutMilliseconds = options.timeoutMilliseconds ?? defaultSyncTimeoutMilliseconds;
  const result = spawnSync(command, args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    env: options.env ?? process.env,
    stdio: options.quiet ? "pipe" : ["ignore", "pipe", "pipe"],
    timeout: timeoutMilliseconds,
    windowsHide: true,
  });
  if (result.error?.code === "ETIMEDOUT") {
    throw new Error(`${label} timed out after ${timeoutMilliseconds} ms`);
  }
  if (result.error) throw new Error(`${label} could not run ${command}: ${result.error.message}`);
  if (result.signal) throw new Error(`${label} was terminated by ${result.signal}`);
  return result;
}

function runChecked(command, args, label, options = {}) {
  const result = spawnBounded(command, args, label, options);
  if (result.status !== 0) {
    throw new Error(`${label} failed (${result.status}): ${(result.stderr || result.stdout || "").trim()}`);
  }
  return (result.stdout ?? "").trim();
}

async function reserveFreePort() {
  return await new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : undefined;
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
}

function installSignalHandlers() {
  for (const [signal, exitCode] of [["SIGINT", 130], ["SIGTERM", 143]]) {
    const handler = () => {
      if (receivedSignal) return;
      receivedSignal = signal;
      process.exitCode = exitCode;
      validationAbort.abort(new Error(`validation interrupted by ${signal}`));
      void cleanup().catch(() => undefined);
    };
    signalHandlers.set(signal, handler);
    process.once(signal, handler);
  }
}

function removeSignalHandlers() {
  for (const [signal, handler] of signalHandlers) process.removeListener(signal, handler);
}

function cleanup() {
  cleanupPromise ??= (async () => {
    const errors = [];
    try {
      await stopApplication();
    } catch (error) {
      errors.push(error);
    }
    try {
      await removeContainerAndAssertAbsent();
    } catch (error) {
      errors.push(error);
    }
    if (errors.length > 0) throw new AggregateError(errors, "Disposable smoke cleanup failed");
  })();
  return cleanupPromise;
}

async function stopApplication() {
  if (!applicationProcess) return;
  if (!applicationProcess.pid && applicationSpawnError) {
    await waitForChildExit(applicationProcess, 1_000);
    applicationProcess.stdout.destroy();
    applicationProcess.stderr.destroy();
    return;
  }
  if (applicationProcess.exitCode === null) {
    if (process.platform === "win32") {
      runChecked("taskkill", ["/PID", String(applicationProcess.pid), "/T", "/F"], "Java process termination", {
        quiet: true,
        timeoutMilliseconds: 10_000,
      });
    } else {
      applicationProcess.kill("SIGTERM");
    }
    await waitForChildExit(applicationProcess, 5_000);
  }
  if (applicationProcess.exitCode === null) {
    applicationProcess.kill("SIGKILL");
    await waitForChildExit(applicationProcess, 5_000);
  }
  applicationProcess.stdout.destroy();
  applicationProcess.stderr.destroy();
  assert(applicationProcess.exitCode !== null, "application process did not terminate during cleanup");
}

async function waitForChildExit(child, timeoutMilliseconds) {
  if (child.exitCode !== null) return;
  await Promise.race([
    new Promise((resolve) => child.once("exit", resolve)),
    delay(timeoutMilliseconds),
  ]);
}

async function removeContainerAndAssertAbsent() {
  if (!dockerRunAttempted) return;
  const deadline = Date.now() + 15_000;
  const cleanupErrors = [];
  let absentSince;
  while (Date.now() < deadline) {
    const candidates = discoverDisposableContainers();
    if (candidates.length > 0) {
      absentSince = undefined;
      for (const candidate of candidates) {
        const inspected = inspectContainerForCleanup(candidate.id);
        if (!inspected) continue;
        const inspectedName = String(inspected.Name ?? "").replace(/^\//u, "");
        const ownedByName = inspectedName === containerName;
        const ownedByLabel = inspected.Config?.Labels?.[disposableRunLabel] === disposableRunId;
        assert(
          ownedByName || ownedByLabel,
          `refusing to remove unowned container ${candidate.id} (${inspectedName})`,
        );
        try {
          const removal = spawnBounded(
            "docker",
            ["rm", "--volumes", "--force", candidate.id],
            `Disposable container removal ${candidate.id}`,
            { timeoutMilliseconds: 10_000 },
          );
          if (removal.status !== 0 && !isNoSuchContainer(removal)) {
            cleanupErrors.push(new Error(
              `Disposable container removal ${candidate.id} failed (${removal.status}): `
                + `${(removal.stderr || removal.stdout || "").trim()}`,
            ));
          }
        } catch (error) {
          cleanupErrors.push(error);
        }
      }
    } else {
      absentSince ??= Date.now();
      if (Date.now() - absentSince >= 750) {
        const directInspection = inspectContainerForCleanup(containerName);
        assert(!directInspection, `container ${containerName} remains after label/name cleanup`);
        if (cleanupErrors.length > 0) {
          throw new AggregateError(cleanupErrors, "Disposable container cleanup commands failed");
        }
        return;
      }
    }
    await delay(200);
  }
  throw new AggregateError(
    cleanupErrors,
    `disposable container cleanup exceeded 15000 ms; remaining=${discoverDisposableContainers().map((entry) => entry.id).join(",")}`,
  );
}

function discoverDisposableContainers() {
  const candidates = new Map();
  for (const filter of [
    `label=${disposableRunLabel}=${disposableRunId}`,
    `name=${containerName}`,
  ]) {
    const output = runChecked(
      "docker",
      ["ps", "--all", "--no-trunc", "--filter", filter, "--format", "{{.ID}}\t{{.Names}}"],
      `Disposable container discovery (${filter})`,
      { timeoutMilliseconds: 5_000 },
    );
    for (const row of outputLines(output)) {
      const [id, name] = row.split("\t", 2);
      if (!id || !name) throw new Error(`invalid Docker discovery row: ${row}`);
      if (filter.startsWith("name=") && name !== containerName) continue;
      candidates.set(id, { id, name });
    }
  }
  return [...candidates.values()];
}

function inspectContainerForCleanup(reference) {
  const inspection = spawnBounded(
    "docker",
    ["inspect", reference],
    `Disposable container post-remove inspection ${reference}`,
    { timeoutMilliseconds: 5_000 },
  );
  if (inspection.status === 0) {
    const parsed = JSON.parse(inspection.stdout);
    assert(Array.isArray(parsed) && parsed.length === 1, `invalid Docker inspect response for ${reference}`);
    return parsed[0];
  }
  if (isNoSuchContainer(inspection)) return null;
  throw new Error(
    `Disposable container inspection ${reference} failed (${inspection.status}): `
      + `${(inspection.stderr || inspection.stdout || "").trim()}`,
  );
}

function isNoSuchContainer(result) {
  return /no such (?:object|container)/iu.test(`${result.stderr ?? ""}\n${result.stdout ?? ""}`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function abortableDelay(milliseconds) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds);
    validationAbort.signal.addEventListener("abort", () => {
      clearTimeout(timer);
      reject(validationAbort.signal.reason ?? new Error("validation aborted"));
    }, { once: true });
  });
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function formatError(error) {
  if (error instanceof AggregateError) {
    return `${error.message}: ${error.errors.map(formatError).join("; ")}`;
  }
  return error instanceof Error ? error.message : String(error);
}
