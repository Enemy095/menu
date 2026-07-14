import { spawn, spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { access, readFile } from "node:fs/promises";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import path from "node:path";
import process from "node:process";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const productionJar = path.join(repositoryRoot, "apps", "api", "build", "libs", "menu-platform-api.jar");
const acceptanceJar = path.join(repositoryRoot, "apps", "api", "build", "libs", "menu-platform-api-acceptance.jar");
const webBuildId = path.join(repositoryRoot, "apps", "web", ".next", "BUILD_ID");
const approvedBundle = path.join(repositoryRoot, "docs", "migration", "data", "stambul-approved-v1.json");
const expectedNormalized = path.join(repositoryRoot, "docs", "fixtures", "stambul", "expected-normalized-v1.json");
const postgresImage = "postgres:17.5-alpine3.21";
const minioImage = "minio/minio:RELEASE.2025-04-22T22-12-26Z";
const minioClientImage = "minio/mc:RELEASE.2025-04-16T18-13-26Z";
const runId = randomUUID();
const runLabel = "com.salamu.validation.acceptance-run-id";
const shortRunId = runId.slice(0, 8);
const postgresContainer = `emenu-acceptance-pg-${process.pid}-${shortRunId}`;
const minioContainer = `emenu-acceptance-s3-${process.pid}-${shortRunId}`;
const databaseName = "stambul_acceptance";
const databaseUser = "stambul_acceptance";
const databasePassword = `acceptance-${randomUUID()}`;
const assetAccessKey = `acceptance${shortRunId}`;
const assetSecretKey = `acceptance-${randomUUID()}`;
const assetBucket = "stambul-acceptance-assets";
const syntheticDestinationId = randomUUID();
const syntheticSecretReference = "secret://acceptance/no-network";
const defaultCommandTimeout = 30_000;
const abortController = new AbortController();
const capturedOutput = [];
const ownedContainers = new Set();
const childProcesses = new Set();
const signalHandlers = new Map();
let applicationProcess;
let webProcess;
let applicationSpawnError;
let webSpawnError;
let cleanupPromise;
let receivedSignal;
let acceptancePassed = false;
let minioClientSequence = 0;

installSignalHandlers();
let failure;
try {
  await runAcceptance();
} catch (error) {
  failure = error;
} finally {
  try {
    await cleanup();
  } catch (cleanupError) {
    failure = failure
      ? new AggregateError([failure, cleanupError], "Acceptance and cleanup both failed")
      : cleanupError;
  }
  removeSignalHandlers();
}

if (failure) {
  console.error(`Stambul full-stack acceptance failed: ${formatError(failure)}`);
  if (capturedOutput.length > 0) {
    console.error("Last bounded process output:");
    console.error(capturedOutput.slice(-100).join("\n"));
  }
  process.exitCode = process.exitCode || 1;
} else if (acceptancePassed) {
  console.log(
    "Stambul full-stack acceptance passed: approved import, exact config/menu, SSR, "
      + "idempotent order persistence and no-network worker retry/success verified; cleanup proven.",
  );
}

async function runAcceptance() {
  const { environment, scrubbedNames } = scrubEnvironment(process.env);
  runChecked("docker", ["version", "--format", "{{.Server.Version}}"], "Docker daemon check", {
    timeoutMilliseconds: 10_000,
  });
  runChecked("java", ["-version"], "Java runtime check", {
    quiet: true,
    env: environment,
    timeoutMilliseconds: 10_000,
  });
  await Promise.all([access(approvedBundle), access(expectedNormalized)]);
  const [bundle, expected] = await Promise.all([
    readFile(approvedBundle, "utf8").then(JSON.parse),
    readFile(expectedNormalized, "utf8").then(JSON.parse),
  ]);
  assertApprovedBundle(bundle, expected);

  prepareBuilds();
  await Promise.all([access(productionJar), access(acceptanceJar), access(webBuildId)]);
  abortController.signal.throwIfAborted();

  const databasePort = await reserveFreePort();
  const assetPort = await reserveFreePort();
  const applicationPort = await reserveFreePort();
  const webPort = await reserveFreePort();

  startPostgres(databasePort);
  startMinio(assetPort);
  await Promise.all([waitForPostgres(), waitForMinio(assetPort)]);
  createAssetBucket();

  applicationProcess = startApplication({
    jar: productionJar,
    environment,
    databasePort,
    assetPort,
    applicationPort,
    workerEnabled: false,
  });
  await waitForApiReadiness(applicationPort);
  await stopProcess(applicationProcess, "schema bootstrap API");
  applicationProcess = undefined;

  const dryRunResult = runImporter(environment, databasePort, assetPort, "dry-run");
  assertImporterResult(dryRunResult, expected, "READY");
  assertImporterDryRunLeftDatabaseEmpty();
  const applyResult = runImporter(environment, databasePort, assetPort, "apply");
  assertImporterResult(applyResult, expected, "CREATED");
  console.log(
    `Approved import signatures: config=${applyResult.configurationRepresentationHash} `
      + `menu=${applyResult.menuRepresentationHash}`,
  );
  assertImportedDatabaseBaseline(bundle, expected);
  assertImportedAsset(expected);
  insertSyntheticDestination();

  applicationProcess = startApplication({
    jar: acceptanceJar,
    environment,
    databasePort,
    assetPort,
    applicationPort,
    workerEnabled: true,
  });
  await waitForApiReadiness(applicationPort);
  webProcess = startWeb(environment, applicationPort, webPort);
  await waitForWeb(webPort);

  const { configuration, menu } = await assertPublicApi(applicationPort, expected, applyResult);
  await assertPublicWeb(webPort);
  await assertOrderAndWorker(webPort, configuration, menu);

  console.log(
    `Acceptance assertions complete; ${scrubbedNames.length} contaminating environment variables were removed.`,
  );
  acceptancePassed = true;
}

function assertApprovedBundle(bundle, expected) {
  assert(bundle?.schemaVersion === 1, "approved bundle schemaVersion must be 1");
  assert(bundle?.selection === "STAMBUL_APPROVED", "only STAMBUL_APPROVED may enter acceptance");
  assert(bundle?.datasetKey === "stambul", "approved bundle datasetKey must be stambul");
  assert(bundle?.localTestBaseline?.notificationDestination === null, "approved import must not configure a notification destination");
  assert(bundle?.localTestBaseline?.workerEnabled === false, "approved import must keep the worker disabled");
  assert(bundle?.localTestBaseline?.canonicalUrl === "https://stambul.localhost", "unexpected canonical URL");
  const raw = JSON.stringify(bundle).toLowerCase();
  assert(!raw.includes("adamas") && !raw.includes("адамaс") && !raw.includes("адамас"), "approved Stambul bundle contains Adamas data");
  assert(bundle?.asset?.sha256 === "90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b", "unexpected Stambul logo hash");
  assert(bundle?.menu?.categories?.flatMap((category) => category.items ?? []).length === 23, "approved bundle must contain exactly 23 menu items");
  assert(expected?.schemaVersion === 1, "normalized oracle schemaVersion must be 1");
  assert(expected?.selection === bundle.selection, "bundle and normalized oracle selection differ");
  assert(expected?.deterministicIds?.namespaceUuid === bundle.uuidNamespace, "bundle and oracle UUID namespaces differ");
  assert(expected?.normalizedGraph?.restaurant?.externalKey === bundle.datasetKey, "bundle and oracle dataset keys differ");
  assert(expected?.normalizedGraph?.assets?.[0]?.sha256 === bundle.asset.sha256, "bundle and oracle asset hashes differ");
  assert(expected?.normalizedGraph?.menu?.items?.length === 23, "normalized oracle must contain exactly 23 menu items");
}

function prepareBuilds() {
  runChecked(
    "node",
    ["--test", "docs/fixtures/stambul/verify-expected-normalized-v1.mjs"],
    "Independent normalized Stambul oracle verification",
    { timeoutMilliseconds: 30_000 },
  );
  const gradle = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
  runChecked(
    gradle,
    ["--no-daemon", ":apps:api:verifyAcceptanceArtifactIsolation"],
    "API production/acceptance artifact build and isolation check",
    { timeoutMilliseconds: 240_000 },
  );
  runChecked(
    "pnpm",
    ["--filter", "web", "build"],
    "Next.js production build",
    { timeoutMilliseconds: 240_000 },
  );
}

function startPostgres(port) {
  runChecked(
    "docker",
    [
      "run", "--detach", "--rm",
      "--name", postgresContainer,
      "--label", "com.salamu.validation.disposable=true",
      "--label", `${runLabel}=${runId}`,
      "--env", `POSTGRES_DB=${databaseName}`,
      "--env", `POSTGRES_USER=${databaseUser}`,
      "--env", `POSTGRES_PASSWORD=${databasePassword}`,
      "--publish", `127.0.0.1:${port}:5432`,
      "--tmpfs", "/var/lib/postgresql/data:rw,noexec,nosuid,size=512m",
      postgresImage,
    ],
    "Disposable PostgreSQL startup",
    { timeoutMilliseconds: 120_000 },
  );
  ownedContainers.add(postgresContainer);
  assertDisposableContainer(postgresContainer, postgresImage, "/var/lib/postgresql/data", "5432/tcp", port);
}

function startMinio(port) {
  runChecked(
    "docker",
    [
      "run", "--detach", "--rm",
      "--name", minioContainer,
      "--label", "com.salamu.validation.disposable=true",
      "--label", `${runLabel}=${runId}`,
      "--env", `MINIO_ROOT_USER=${assetAccessKey}`,
      "--env", `MINIO_ROOT_PASSWORD=${assetSecretKey}`,
      "--publish", `127.0.0.1:${port}:9000`,
      "--tmpfs", "/data:rw,noexec,nosuid,size=128m",
      minioImage,
      "server", "/data", "--address", ":9000", "--console-address", ":9001",
    ],
    "Disposable MinIO startup",
    { timeoutMilliseconds: 120_000 },
  );
  ownedContainers.add(minioContainer);
  assertDisposableContainer(minioContainer, minioImage, "/data", "9000/tcp", port);
}

function assertDisposableContainer(name, image, tmpfsPath, exposedPort, hostPort) {
  const inspected = inspectContainer(name);
  assert(inspected.Config?.Image === image, `${name} uses unexpected image ${inspected.Config?.Image}`);
  assert(inspected.Config?.Labels?.["com.salamu.validation.disposable"] === "true", `${name} is missing disposable label`);
  assert(inspected.Config?.Labels?.[runLabel] === runId, `${name} is missing unique run label`);
  assert(Array.isArray(inspected.Mounts) && inspected.Mounts.length === 0, `${name} has a bind or volume mount`);
  const tmpfs = inspected.HostConfig?.Tmpfs?.[tmpfsPath];
  assert(typeof tmpfs === "string" && tmpfs.includes("rw"), `${name} is missing ${tmpfsPath} tmpfs`);
  const bindings = inspected.NetworkSettings?.Ports?.[exposedPort];
  assert(Array.isArray(bindings) && bindings.length === 1, `${name} has an unexpected port binding`);
  assert(bindings[0].HostIp === "127.0.0.1", `${name} is not loopback-bound`);
  assert(bindings[0].HostPort === String(hostPort), `${name} host port mismatch`);
}

function inspectContainer(name) {
  const raw = runChecked("docker", ["inspect", name], `Inspect ${name}`, { timeoutMilliseconds: 10_000 });
  const result = JSON.parse(raw);
  assert(Array.isArray(result) && result.length === 1, `Docker inspect did not return ${name}`);
  return result[0];
}

async function waitForPostgres() {
  await waitUntil("PostgreSQL readiness", 30_000, async () => {
    const result = spawnBounded(
      "docker",
      ["exec", postgresContainer, "pg_isready", "--username", databaseUser, "--dbname", databaseName],
      { timeoutMilliseconds: 5_000 },
    );
    return result.status === 0;
  });
}

async function waitForMinio(port) {
  await waitUntil("MinIO readiness", 30_000, async () => {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/minio/health/live`, {
        signal: AbortSignal.any([abortController.signal, AbortSignal.timeout(2_000)]),
      });
      void response.body?.cancel().catch(() => undefined);
      return response.ok;
    } catch {
      return false;
    }
  });
}

function createAssetBucket() {
  runMinioClient(`mc mb --ignore-existing local/${assetBucket} >/dev/null`);
}

function startApplication({
  jar,
  environment,
  databasePort,
  assetPort,
  applicationPort,
  workerEnabled,
}) {
  applicationSpawnError = undefined;
  const argumentsList = [
    "-jar", jar,
    ...(workerEnabled ? ["--spring.profiles.active=acceptance"] : []),
    `--spring.datasource.url=jdbc:postgresql://127.0.0.1:${databasePort}/${databaseName}`,
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
    `--app.assets.storage.endpoint=http://127.0.0.1:${assetPort}`,
    "--app.assets.storage.region=us-east-1",
    `--app.assets.storage.bucket=${assetBucket}`,
    `--app.assets.storage.access-key=${assetAccessKey}`,
    `--app.assets.storage.secret-key=${assetSecretKey}`,
    "--app.assets.storage.path-style-access=true",
    "--app.assets.cdn-base-url=https://assets.stambul.localhost",
    `--app.external.public-base-url=http://127.0.0.1:${applicationPort}`,
    "--app.external.asset-base-url=https://assets.stambul.localhost",
    `--app.worker.enabled=${workerEnabled}`,
    ...(workerEnabled ? [
      `--app.worker.worker-id=stambul-acceptance-${shortRunId}`,
      "--app.worker.poll-interval=100ms",
      "--app.worker.batch-size=1",
      "--app.worker.concurrency=1",
      "--app.worker.max-destinations=1",
      "--app.worker.lease-duration=10s",
      "--app.worker.provider-timeout=1s",
      "--app.worker.max-attempts=3",
      "--app.worker.initial-backoff=2s",
      "--app.worker.max-backoff=2s",
      "--app.worker.jitter-ratio=0",
      "--app.worker.shutdown-timeout=2s",
      "--app.acceptance.notification.enabled=true",
      "--app.acceptance.notification.failures-before-success=1",
    ] : []),
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
  childProcesses.add(child);
  child.once("error", (error) => {
    applicationSpawnError = error;
    captureOutput(`API spawn error: ${error.message}`);
  });
  child.stdout.on("data", captureOutput);
  child.stderr.on("data", captureOutput);
  return child;
}

function startWeb(environment, applicationPort, webPort) {
  webSpawnError = undefined;
  const webEnvironment = {
    ...environment,
    NODE_ENV: "production",
    MENU_API_BASE_URL: `http://127.0.0.1:${applicationPort}`,
    PUBLIC_BASE_URL: "https://stambul.localhost",
    PORT: String(webPort),
    HOSTNAME: "127.0.0.1",
  };
  const command = longRunningCommand("pnpm", ["--filter", "web", "start", "--hostname", "127.0.0.1", "--port", String(webPort)]);
  const child = spawn(command.executable, command.arguments, {
    cwd: repositoryRoot,
    env: webEnvironment,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
    windowsVerbatimArguments: command.windowsVerbatimArguments ?? false,
  });
  childProcesses.add(child);
  child.once("error", (error) => {
    webSpawnError = error;
    captureOutput(`Web spawn error: ${error.message}`);
  });
  child.stdout.on("data", captureOutput);
  child.stderr.on("data", captureOutput);
  return child;
}

function captureOutput(chunk) {
  capturedOutput.push(...String(chunk).split(/\r?\n/u).filter(Boolean));
  if (capturedOutput.length > 600) capturedOutput.splice(0, capturedOutput.length - 600);
}

async function waitForApiReadiness(port) {
  await waitUntil("API readiness", 90_000, async () => {
    if (applicationSpawnError) throw applicationSpawnError;
    if (applicationProcess?.exitCode !== null) {
      throw new Error(`API exited before readiness with code ${applicationProcess?.exitCode}`);
    }
    try {
      const response = await requestJson(`http://127.0.0.1:${port}/actuator/health/readiness`);
      return response.status === 200 && response.body?.status === "UP";
    } catch {
      return false;
    }
  });
}

async function waitForWeb(port) {
  await waitUntil("Next.js readiness", 60_000, async () => {
    if (webSpawnError) throw webSpawnError;
    if (webProcess?.exitCode !== null) {
      throw new Error(`web exited before readiness with code ${webProcess?.exitCode}`);
    }
    try {
      const response = await fetch(`http://127.0.0.1:${port}/`, {
        headers: { Accept: "text/html" },
        signal: AbortSignal.any([abortController.signal, AbortSignal.timeout(5_000)]),
      });
      void response.body?.cancel().catch(() => undefined);
      return response.status === 200;
    } catch {
      return false;
    }
  });
}

function runImporter(environment, databasePort, assetPort, mode) {
  const importerEnvironment = {
    ...environment,
    DATA_IMPORT_JDBC_URL: `jdbc:postgresql://127.0.0.1:${databasePort}/${databaseName}`,
    DATA_IMPORT_DB_USER: databaseUser,
    DATA_IMPORT_DB_PASSWORD: databasePassword,
    DATA_IMPORT_S3_ENDPOINT: `http://127.0.0.1:${assetPort}`,
    DATA_IMPORT_S3_REGION: "us-east-1",
    DATA_IMPORT_S3_BUCKET: assetBucket,
    DATA_IMPORT_S3_ACCESS_KEY: assetAccessKey,
    DATA_IMPORT_S3_SECRET_KEY: assetSecretKey,
    DATA_IMPORT_S3_PATH_STYLE: "true",
    DATA_IMPORT_CDN_BASE_URL: "https://assets.stambul.localhost",
  };
  const gradle = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
  const relativeBundle = path.relative(repositoryRoot, approvedBundle).split(path.sep).join("/");
  const output = runChecked(
    gradle,
    [
      "--no-daemon",
      ":apps:api:runDataImport",
      `--args=--bundle=${relativeBundle} --asset-root=. --mode=${mode} --target=local-test`,
    ],
    `Approved Stambul importer ${mode}`,
    { env: importerEnvironment, timeoutMilliseconds: 240_000 },
  );
  const resultLine = outputLines(output).find((line) => line.startsWith("DATA_IMPORT_RESULT "));
  assert(resultLine, `importer ${mode} omitted its machine-readable result`);
  return JSON.parse(resultLine.slice("DATA_IMPORT_RESULT ".length));
}

function assertImporterResult(result, expected, outcome) {
  assert(result.outcome === outcome, `importer outcome ${result.outcome} differs from ${outcome}`);
  assert(result.selection === expected.selection, "importer result selection differs from oracle");
  assert(result.bundleSha256 === expected.approvedImporterBundle.sha256, "importer result bundle hash differs from oracle");
  assert(result.assetId === expected.deterministicIds.anchorIds["asset:logo"], "importer asset ID differs from oracle");
  assert(result.restaurantId === expected.deterministicIds.anchorIds["restaurant:stambul"], "importer restaurant ID differs from oracle");
  assert(result.menuVersionId === expected.deterministicIds.anchorIds["menu:stambul:1"], "importer menu ID differs from oracle");
  assert(result.categoryCount === expected.normalizedGraph.menu.categories.length, "importer category count differs from oracle");
  assert(result.itemCount === expected.normalizedGraph.menu.items.length, "importer item count differs from oracle");
  assert(result.assetSha256 === expected.normalizedGraph.assets[0].sha256, "importer asset hash differs from oracle");
  assert(/^[0-9a-f]{64}$/u.test(result.effectiveSha256), "importer effective hash is invalid");
  if (outcome === "CREATED") {
    assert(/^[0-9a-f]{64}$/u.test(result.configurationRepresentationHash), "importer config representation hash is invalid");
    assert(/^[0-9a-f]{64}$/u.test(result.menuRepresentationHash), "importer menu representation hash is invalid");
  } else {
    assert(result.configurationRepresentationHash === null, "empty-target dry-run returned a config representation hash");
    assert(result.menuRepresentationHash === null, "empty-target dry-run returned a menu representation hash");
  }
}

function assertImporterDryRunLeftDatabaseEmpty() {
  const counts = queryKeyValue(`
    SELECT 'site_configuration', count(*) FROM site_configuration
    UNION ALL SELECT 'assets', count(*) FROM assets
    UNION ALL SELECT 'restaurants', count(*) FROM restaurants
    UNION ALL SELECT 'menu_versions', count(*) FROM menu_versions
    UNION ALL SELECT 'notification_destinations', count(*) FROM notification_destinations
    UNION ALL SELECT 'orders', count(*) FROM orders
    UNION ALL SELECT 'outbox_events', count(*) FROM outbox_events;
  `, "Dry-run zero-write assertion");
  for (const [table, count] of counts) {
    assert(count === "0", `importer dry-run wrote ${table}=${count}`);
  }
  assertAssetAbsent();
}

function assertImportedDatabaseBaseline(bundle, normalized) {
  const counts = queryKeyValue(`
    SELECT 'site_configuration', count(*) FROM site_configuration
    UNION ALL SELECT 'site_branding', count(*) FROM site_branding
    UNION ALL SELECT 'site_profile', count(*) FROM site_profile
    UNION ALL SELECT 'restaurants', count(*) FROM restaurants
    UNION ALL SELECT 'assets', count(*) FROM assets
    UNION ALL SELECT 'menu_versions', count(*) FROM menu_versions
    UNION ALL SELECT 'menu_categories', count(*) FROM menu_categories
    UNION ALL SELECT 'menu_items', count(*) FROM menu_items
    UNION ALL SELECT 'menu_modifier_groups', count(*) FROM menu_modifier_groups
    UNION ALL SELECT 'menu_modifier_options', count(*) FROM menu_modifier_options
    UNION ALL SELECT 'notification_destinations', count(*) FROM notification_destinations
    UNION ALL SELECT 'orders', count(*) FROM orders
    UNION ALL SELECT 'order_items', count(*) FROM order_items
    UNION ALL SELECT 'outbox_events', count(*) FROM outbox_events
    UNION ALL SELECT 'notification_deliveries', count(*) FROM notification_deliveries;
  `, "Imported graph row-count assertion");
  const expected = new Map([
    ["site_configuration", "1"], ["site_branding", "1"], ["site_profile", "1"],
    ["restaurants", "1"], ["assets", "1"], ["menu_versions", "1"],
    ["menu_categories", "4"], ["menu_items", "23"], ["menu_modifier_groups", "0"],
    ["menu_modifier_options", "0"], ["notification_destinations", "0"], ["orders", "0"],
    ["order_items", "0"], ["outbox_events", "0"], ["notification_deliveries", "0"],
  ]);
  assertDeepEqual(Object.fromEntries(counts), Object.fromEntries(expected), "imported row-count signature");

  const identity = queryKeyValue(`
    SELECT 'external_key', external_key FROM restaurants
    UNION ALL SELECT 'slug', slug FROM restaurants
    UNION ALL SELECT 'display_name', display_name FROM restaurants
    UNION ALL SELECT 'locale', locale FROM site_configuration
    UNION ALL SELECT 'currency', currency FROM site_configuration
    UNION ALL SELECT 'timezone', timezone FROM site_configuration
    UNION ALL SELECT 'asset_sha256', sha256 FROM assets;
  `, "Imported Stambul identity assertion");
  assert(identity.get("external_key") === "stambul", "imported external key is not stambul");
  assert(identity.get("slug") === "stambul", "imported slug is not stambul");
  assert(identity.get("display_name") === bundle.site.displayName, "imported display name differs from approval");
  assert(identity.get("locale") === "ru-RU", "imported locale differs from approval");
  assert(identity.get("currency") === "RUB", "imported currency differs from approval");
  assert(identity.get("timezone") === "Europe/Moscow", "imported timezone differs from approval");
  assert(identity.get("asset_sha256") === bundle.asset.sha256, "imported logo hash differs from approval");

  const deterministicIds = queryKeyValue(`
    SELECT 'asset', min(id::text) FROM assets
    UNION ALL SELECT 'restaurant', min(id::text) FROM restaurants
    UNION ALL SELECT 'menu', min(id::text) FROM menu_versions;
  `, "Imported deterministic ID assertion");
  assert(deterministicIds.get("asset") === normalized.deterministicIds.anchorIds["asset:logo"], "imported asset UUID differs from oracle");
  assert(deterministicIds.get("restaurant") === normalized.deterministicIds.anchorIds["restaurant:stambul"], "imported restaurant UUID differs from oracle");
  assert(deterministicIds.get("menu") === normalized.deterministicIds.anchorIds["menu:stambul:1"], "imported menu UUID differs from oracle");

  const contamination = Number(queryScalar(`
    SELECT
      (SELECT count(*) FROM restaurants row WHERE lower(to_jsonb(row)::text) LIKE '%adamas%' OR lower(to_jsonb(row)::text) LIKE '%адамас%')
      + (SELECT count(*) FROM site_branding row WHERE lower(to_jsonb(row)::text) LIKE '%adamas%' OR lower(to_jsonb(row)::text) LIKE '%адамас%')
      + (SELECT count(*) FROM site_profile row WHERE lower(to_jsonb(row)::text) LIKE '%adamas%' OR lower(to_jsonb(row)::text) LIKE '%адамас%')
      + (SELECT count(*) FROM menu_categories row WHERE lower(to_jsonb(row)::text) LIKE '%adamas%' OR lower(to_jsonb(row)::text) LIKE '%адамас%')
      + (SELECT count(*) FROM menu_items row WHERE lower(to_jsonb(row)::text) LIKE '%adamas%' OR lower(to_jsonb(row)::text) LIKE '%адамас%')
      + (SELECT count(*) FROM assets WHERE sha256 = '68eed81f6b021efd58113f4ab6edc8feef68d91336f600729c776a07ab6caaab');
  `, "Adamas contamination assertion"));
  assert(contamination === 0, `imported database contains ${contamination} Adamas markers`);
}

function insertSyntheticDestination() {
  queryDatabase(`
    INSERT INTO notification_destinations (
      id, channel, status, display_name, secret_reference, metadata
    ) VALUES (
      ${sqlLiteral(syntheticDestinationId)}::uuid,
      'telegram',
      'active',
      'Disposable acceptance no-network provider',
      ${sqlLiteral(syntheticSecretReference)},
      '{"mode":"deterministic-no-network"}'::jsonb
    );
  `, "Synthetic disposable notification destination insertion");
}

function assertAssetAbsent() {
  const result = runMinioClient(
    `mc stat local/${assetBucket}/assets/logo/90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b.png >/dev/null`,
    false,
  );
  assert(result.status !== 0, "importer dry-run wrote the Stambul logo to object storage");
}

function assertImportedAsset(normalized) {
  const objectKey = normalized.normalizedGraph.assets[0].canonicalObjectKey;
  const result = runMinioClient(
    `mc stat local/${assetBucket}/${objectKey} >/dev/null`,
    false,
  );
  assert(result.status === 0, "approved Stambul logo is absent from disposable object storage");
}

function runMinioClient(command, checked = true) {
  const clientContainer = `emenu-acceptance-mc-${process.pid}-${shortRunId}-${++minioClientSequence}`;
  const argumentsList = [
    "run", "--rm",
    "--name", clientContainer,
    "--label", "com.salamu.validation.disposable=true",
    "--label", `${runLabel}=${runId}`,
    "--network", `container:${minioContainer}`,
    "--env", `MINIO_ROOT_USER=${assetAccessKey}`,
    "--env", `MINIO_ROOT_PASSWORD=${assetSecretKey}`,
    "--entrypoint", "/bin/sh",
    minioClientImage,
    "-ec",
    `mc alias set local http://127.0.0.1:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null && ${command}`,
  ];
  ownedContainers.add(clientContainer);
  return checked
    ? runChecked("docker", argumentsList, "Disposable MinIO assertion", { timeoutMilliseconds: 30_000 })
    : spawnBounded("docker", argumentsList, { timeoutMilliseconds: 30_000 });
}

async function assertPublicApi(port, expected, importResult) {
  const configResponse = await requestJson(`http://127.0.0.1:${port}/api/v1/public/config`);
  const menuResponse = await requestJson(`http://127.0.0.1:${port}/api/v1/public/menu`);
  assert(configResponse.status === 200, `config returned HTTP ${configResponse.status}`);
  assert(menuResponse.status === 200, `menu returned HTTP ${menuResponse.status}`);
  assert(configResponse.headers.get("etag") === `"config-${importResult.configurationRepresentationHash}"`, "config ETag differs from importer representation signature");
  assert(menuResponse.headers.get("etag") === `"menu-v1-${importResult.menuRepresentationHash}"`, "menu ETag differs from importer representation signature");

  const configuration = configResponse.body;
  const menu = menuResponse.body;
  assert(configuration.restaurantId === menu.restaurantId, "config/menu restaurant IDs differ");
  assert(configuration.restaurantId === expected.deterministicIds.anchorIds["restaurant:stambul"], "restaurant ID differs from normalized oracle");

  const graph = expected.normalizedGraph;
  const publicDeliveryConfiguration = {
    enabled: graph.deliveryConfiguration.enabled,
    addressRequired: graph.deliveryConfiguration.addressRequired,
    feePolicy: graph.deliveryConfiguration.feePolicy,
  };

  const expectedConfiguration = {
    branding: {
      logoUrl: `https://assets.stambul.localhost/${graph.assets[0].canonicalObjectKey}`,
      logoAlt: graph.siteBranding.logoAlt,
      hero: {
        title: graph.siteBranding.hero.title,
        subtitle: graph.siteBranding.hero.subtitle,
        ctaLabel: graph.siteBranding.hero.ctaLabel,
        benefits: graph.siteBranding.hero.benefits,
      },
      designTokens: graph.siteBranding.designTokens,
      localizedTexts: graph.siteBranding.localizedTexts,
    },
    seo: {
      title: graph.siteBranding.seo.title,
      description: graph.siteBranding.seo.description,
      canonicalUrl: graph.siteBranding.seo.canonicalUrl,
    },
    profile: {
      publicPhone: graph.siteProfile.publicPhone,
      publicPhoneLabel: graph.siteProfile.publicPhoneLabel,
      socialLinks: graph.siteProfile.socialLinks,
    },
    features: graph.features,
    orderTypes: graph.orderTypes,
    limits: graph.limits,
    schedule: {
      timezone: graph.siteConfiguration.timezone,
      displayText: graph.siteProfile.workingHoursText,
      days: graph.siteProfile.schedule,
    },
    deliveryConfiguration: publicDeliveryConfiguration,
    locale: graph.siteConfiguration.locale,
    currency: graph.siteConfiguration.currency,
    timezone: graph.siteConfiguration.timezone,
  };
  const actualConfiguration = { ...configuration };
  delete actualConfiguration.restaurantId;
  assertDeepEqual(actualConfiguration, expectedConfiguration, "public config signature");

  const expectedCategories = graph.menu.categories.map(({ categoryId, name, sortOrder }) => ({
    categoryId,
    name,
    sortOrder,
  }));
  assertDeepEqual(menu.categories, expectedCategories, "public category signature");
  const actualItems = menu.items.map((item) => [
    item.menuItemId,
    item.categoryId,
    item.name,
    item.description,
    item.priceMinor,
    item.sortOrder,
  ]);
  const expectedItems = graph.menu.items.map((item) => [
    item.menuItemId,
    item.categoryId,
    item.name,
    item.description,
    item.priceMinor,
    item.sortOrder,
  ]);
  assertDeepEqual(actualItems, expectedItems, "public item text/price/order signature");
  assert(menu.version === graph.menu.version, "public menu version differs from oracle");
  assert(new Date(menu.publishedAt).getTime() === new Date(graph.menu.publishedAt).getTime(), "public menu publishedAt differs from oracle");
  assert(menu.currency === graph.menu.currency, "public menu currency differs from oracle");
  assert(menu.items.every((item) => item.available === true), "not every approved menu item is available");
  assert(menu.items.every((item) => Array.isArray(item.modifierGroups) && item.modifierGroups.length === 0), "approved Stambul menu unexpectedly contains modifiers");
  assert(menu.items.every((item) => !("imageUrl" in item)), "approved Stambul item unexpectedly contains an image");
  assert(!JSON.stringify({ configuration, menu }).toLowerCase().includes("adamas"), "public API exposes Adamas content");

  return { configuration, menu };
}

async function assertPublicWeb(port) {
  const response = await fetch(`http://127.0.0.1:${port}/`, {
    headers: { Accept: "text/html" },
    signal: AbortSignal.any([abortController.signal, AbortSignal.timeout(15_000)]),
  });
  const html = await response.text();
  assert(response.status === 200, `public web returned HTTP ${response.status}`);
  for (const signature of [
    "Ресторан-кафе у Ризвана",
    "Вкус Стамбула рядом с вами",
    "Ролл Цезарь с курицей",
    "Пицца    4 сыра",
    "Бургер куриный",
    "https://stambul.localhost",
  ]) {
    assert(html.includes(signature), `public SSR omitted ${JSON.stringify(signature)}`);
  }
  const lower = html.toLowerCase();
  assert(!lower.includes("adamas") && !lower.includes("адамас"), "public SSR contains Adamas content");
  assert(!lower.includes(syntheticSecretReference), "public SSR leaked the synthetic destination");
}

async function assertOrderAndWorker(webPort, configuration, menu) {
  const selectedItem = menu.items.find((item) => item.menuItemId === "cezar-roll-chicken");
  assert(selectedItem, "approved order item is missing from the public menu");
  const idempotencyKey = randomUUID();
  const request = {
    orderType: "pickup",
    customer: { name: "Локальная проверка", phone: "+70000000000" },
    fulfillment: {},
    items: [{ menuItemId: selectedItem.menuItemId, quantity: 2, modifiers: [] }],
    comment: "disposable acceptance",
    clientMenuVersion: menu.version,
  };
  const endpoint = `http://127.0.0.1:${webPort}/api/v1/public/orders`;
  const created = await postOrder(endpoint, idempotencyKey, request);
  assert(created.status === 201, `order creation returned HTTP ${created.status}`);
  assert(created.headers.get("idempotency-replayed") === "false", "first order response was marked replayed");
  assert(created.body.status === "new", "created order status is not new");
  assert(created.body.serverTotalMinor === selectedItem.priceMinor * 2, "server total is not authoritative menu price x quantity");
  assert(created.body.currency === configuration.currency, "order response currency differs from configuration");
  assert(created.body.needsConfirmation === false, "pickup order unexpectedly needs confirmation");

  const replay = await postOrder(endpoint, idempotencyKey, request);
  assert(replay.status === 201, `idempotent replay returned HTTP ${replay.status}`);
  assert(replay.headers.get("idempotency-replayed") === "true", "identical replay omitted replay header");
  assertDeepEqual(replay.body, created.body, "idempotent replay response");

  const persisted = queryKeyValue(`
    SELECT 'orders', count(*)::text FROM orders
    UNION ALL SELECT 'order_items', count(*)::text FROM order_items
    UNION ALL SELECT 'order_history', count(*)::text FROM order_status_history
    UNION ALL SELECT 'outbox', count(*)::text FROM outbox_events
    UNION ALL SELECT 'order_id', min(id::text) FROM orders
    UNION ALL SELECT 'order_number', min(order_number::text) FROM orders
    UNION ALL SELECT 'status', min(status) FROM orders
    UNION ALL SELECT 'total', min(server_total_minor::text) FROM orders
    UNION ALL SELECT 'currency', min(currency) FROM orders
    UNION ALL SELECT 'item_public_id', min(menu_item_public_id) FROM order_items
    UNION ALL SELECT 'item_name', min(item_name) FROM order_items
    UNION ALL SELECT 'unit_price', min(unit_price_minor::text) FROM order_items
    UNION ALL SELECT 'quantity', min(quantity::text) FROM order_items
    UNION ALL SELECT 'line_total', min(line_total_minor::text) FROM order_items
    UNION ALL SELECT 'modifier_snapshot', min(modifier_snapshot::text) FROM order_items
    UNION ALL SELECT 'outbox_aggregate', min(aggregate_id::text) FROM outbox_events
    UNION ALL SELECT 'outbox_type', min(event_type) FROM outbox_events
    UNION ALL SELECT 'outbox_schema', min(schema_version::text) FROM outbox_events;
  `, "Atomic order/item/outbox persistence assertion");
  const expectedPersistence = {
    orders: "1",
    order_items: "1",
    order_history: "1",
    outbox: "1",
    order_id: created.body.orderId,
    order_number: created.body.orderNumber,
    status: "new",
    total: String(selectedItem.priceMinor * 2),
    currency: "RUB",
    item_public_id: selectedItem.menuItemId,
    item_name: selectedItem.name,
    unit_price: String(selectedItem.priceMinor),
    quantity: "2",
    line_total: String(selectedItem.priceMinor * 2),
    modifier_snapshot: "[]",
    outbox_aggregate: created.body.orderId,
    outbox_type: "order.created",
    outbox_schema: "1",
  };
  assertDeepEqual(Object.fromEntries(persisted), expectedPersistence, "atomic persisted order signature");

  await waitUntil("worker transient failure evidence", 10_000, async () => {
    const state = queryScalar(`
      SELECT processing_state || '|' || attempt_count || '|' || coalesce(last_error_code, '')
      FROM outbox_events;
    `, "Worker transient state query");
    if (state === "pending|1|ACCEPTANCE_TRANSIENT_FAILURE") return true;
    throw new Error(`observed ${state}`);
  });
  const firstDelivery = queryScalar(`
    SELECT attempt_number || '|' || status || '|' || coalesce(error_code, '') || '|'
           || coalesce(provider_message_id, '')
    FROM notification_deliveries
    WHERE attempt_number = 1;
  `, "First notification delivery audit assertion");
  assert(firstDelivery === "1|failed|ACCEPTANCE_TRANSIENT_FAILURE|", `unexpected first delivery audit ${firstDelivery}`);
  assert(queryScalar("SELECT count(*) FROM orders;", "Order survival after provider failure") === "1", "provider failure lost the order");

  await waitUntil("worker retry success evidence", 10_000, async () => {
    const state = queryScalar(`
      SELECT processing_state || '|' || attempt_count || '|' || (processed_at IS NOT NULL)::text
             || '|' || coalesce(last_error_code, '')
      FROM outbox_events;
    `, "Worker terminal state query");
    return state === "published|2|true|";
  });
  const deliveries = outputLines(queryDatabase(`
    SELECT attempt_number || '|' || status || '|' || coalesce(error_code, '') || '|'
           || coalesce(provider_message_id, '')
    FROM notification_deliveries
    ORDER BY attempt_number;
  `, "Notification delivery retry/success audit assertion"));
  assertDeepEqual(deliveries, [
    "1|failed|ACCEPTANCE_TRANSIENT_FAILURE|",
    "2|succeeded||",
  ], "notification delivery audit sequence");
  const terminalCounts = queryKeyValue(`
    SELECT 'orders', count(*) FROM orders
    UNION ALL SELECT 'order_items', count(*) FROM order_items
    UNION ALL SELECT 'outbox_published', count(*) FROM outbox_events WHERE processing_state = 'published'
    UNION ALL SELECT 'outbox_failed', count(*) FROM outbox_events WHERE processing_state = 'failed'
    UNION ALL SELECT 'destinations', count(*) FROM notification_destinations
    UNION ALL SELECT 'real_destinations', count(*) FROM notification_destinations WHERE secret_reference <> ${sqlLiteral(syntheticSecretReference)}
    UNION ALL SELECT 'provider_ids', count(*) FROM notification_deliveries WHERE provider_message_id IS NOT NULL;
  `, "Terminal no-external-call persistence assertion");
  assertDeepEqual(Object.fromEntries(terminalCounts), {
    orders: "1",
    order_items: "1",
    outbox_published: "1",
    outbox_failed: "0",
    destinations: "1",
    real_destinations: "0",
    provider_ids: "0",
  }, "terminal order/outbox/no-network signature");
}

async function postOrder(endpoint, idempotencyKey, body) {
  return await requestJson(endpoint, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(body),
  });
}

function queryDatabase(query, label) {
  return runChecked(
    "docker",
    [
      "exec", postgresContainer,
      "psql",
      "--username", databaseUser,
      "--dbname", databaseName,
      "--tuples-only",
      "--no-align",
      "--field-separator", "|",
      "--set", "ON_ERROR_STOP=1",
      "--command", query,
    ],
    label,
    { timeoutMilliseconds: 20_000 },
  );
}

function queryScalar(query, label) {
  const lines = outputLines(queryDatabase(query, label));
  assert(lines.length === 1, `${label} returned ${lines.length} rows instead of one`);
  return lines[0];
}

function queryKeyValue(query, label) {
  const result = new Map();
  for (const row of outputLines(queryDatabase(query, label))) {
    const separator = row.indexOf("|");
    assert(separator > 0, `${label} returned invalid key/value row ${row}`);
    const key = row.slice(0, separator);
    assert(!result.has(key), `${label} returned duplicate key ${key}`);
    result.set(key, row.slice(separator + 1));
  }
  return result;
}

function outputLines(output) {
  return output.split(/\r?\n/u).map((line) => line.trim()).filter(Boolean);
}

function sqlLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function scrubEnvironment(source) {
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
  return /^(?:SPRING_|SERVER_|MANAGEMENT_|DATABASE_|DATA_IMPORT_|APP_|ASSET_|S3_|MINIO_|OTEL_|LOGGING_|MENU_API_)/iu.test(name)
    || /^(?:PUBLIC_BASE_URL|PORT|HOSTNAME|NODE_ENV|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|JAVA_OPTS|CLASSPATH)$/iu.test(name);
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: { Accept: "application/json", ...(options.headers ?? {}) },
    signal: AbortSignal.any([abortController.signal, AbortSignal.timeout(10_000)]),
  });
  const raw = await response.text();
  let body;
  try {
    body = JSON.parse(raw);
  } catch {
    throw new Error(`${new URL(url).pathname} did not return JSON: ${raw.slice(0, 300)}`);
  }
  return { status: response.status, headers: response.headers, body };
}

async function waitUntil(label, timeoutMilliseconds, predicate) {
  const deadline = Date.now() + timeoutMilliseconds;
  let lastError;
  while (Date.now() < deadline) {
    abortController.signal.throwIfAborted();
    try {
      if (await predicate()) return;
    } catch (error) {
      lastError = error;
      if (error === applicationSpawnError || error === webSpawnError) throw error;
    }
    await abortableDelay(100);
  }
  const suffix = lastError ? ` (${formatError(lastError)})` : "";
  throw new Error(`${label} did not complete within ${timeoutMilliseconds} ms${suffix}`);
}

function spawnBounded(command, argumentsList, options = {}) {
  const prepared = boundedCommand(command, argumentsList);
  const timeoutMilliseconds = options.timeoutMilliseconds ?? defaultCommandTimeout;
  const result = spawnSync(prepared.executable, prepared.arguments, {
    cwd: repositoryRoot,
    encoding: "utf8",
    env: options.env ?? process.env,
    stdio: options.quiet ? "pipe" : ["ignore", "pipe", "pipe"],
    timeout: timeoutMilliseconds,
    windowsHide: true,
    windowsVerbatimArguments: prepared.windowsVerbatimArguments ?? false,
  });
  if (result.error?.code === "ETIMEDOUT") {
    throw new Error(`${command} timed out after ${timeoutMilliseconds} ms`);
  }
  if (result.error) throw new Error(`${command} could not run: ${result.error.message}`);
  if (result.signal) throw new Error(`${command} was terminated by ${result.signal}`);
  return result;
}

function runChecked(command, argumentsList, label, options = {}) {
  const result = spawnBounded(command, argumentsList, options);
  if (result.status !== 0) {
    const output = [result.stdout, result.stderr].filter(Boolean).join("\n").trim();
    throw new Error(`${label} failed (${result.status}): ${output}`);
  }
  return (result.stdout ?? "").trim();
}

function boundedCommand(command, argumentsList) {
  if (process.platform !== "win32" || !/^(?:pnpm|gradlew\.bat)$/iu.test(command)) {
    return { executable: command, arguments: argumentsList, windowsVerbatimArguments: false };
  }
  const commandLine = [command, ...argumentsList].map(quoteWindowsArgument).join(" ");
  return {
    executable: process.env.ComSpec ?? "cmd.exe",
    arguments: ["/d", "/c", commandLine],
    windowsVerbatimArguments: true,
  };
}

function longRunningCommand(command, argumentsList) {
  return boundedCommand(command, argumentsList);
}

function quoteWindowsArgument(value) {
  if (value.startsWith("--args=") && /\s/u.test(value)) {
    return `--args="${value.slice("--args=".length).replaceAll('"', '""')}"`;
  }
  return /[\s"]/u.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
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
      abortController.abort(new Error(`acceptance interrupted by ${signal}`));
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
    for (const [child, label] of [[webProcess, "Next.js"], [applicationProcess, "API"]]) {
      try {
        await stopProcess(child, label);
      } catch (error) {
        errors.push(error);
      }
    }
    for (const container of [...ownedContainers].reverse()) {
      try {
        removeOwnedContainer(container);
      } catch (error) {
        errors.push(error);
      }
    }
    try {
      assertNoOwnedContainers();
    } catch (error) {
      errors.push(error);
    }
    if (errors.length > 0) throw new AggregateError(errors, "Disposable full-stack cleanup failed");
  })();
  return cleanupPromise;
}

async function stopProcess(child, label) {
  if (!child) return;
  if (child.exitCode === null) {
    if (process.platform === "win32") {
      const result = spawnBounded("taskkill", ["/PID", String(child.pid), "/T", "/F"], {
        quiet: true,
        timeoutMilliseconds: 10_000,
      });
      if (result.status !== 0 && !/not found|not running/iu.test(`${result.stdout}\n${result.stderr}`)) {
        throw new Error(`${label} process termination failed: ${(result.stderr || result.stdout).trim()}`);
      }
    } else {
      child.kill("SIGTERM");
    }
    await waitForChildExit(child, 5_000);
  }
  if (child.exitCode === null) {
    child.kill("SIGKILL");
    await waitForChildExit(child, 5_000);
  }
  child.stdout?.destroy();
  child.stderr?.destroy();
  childProcesses.delete(child);
  assert(child.exitCode !== null, `${label} process did not terminate`);
}

async function waitForChildExit(child, timeoutMilliseconds) {
  if (child.exitCode !== null) return;
  await Promise.race([
    new Promise((resolve) => child.once("exit", resolve)),
    delay(timeoutMilliseconds),
  ]);
}

function removeOwnedContainer(name) {
  const inspected = inspectContainerForCleanup(name);
  if (!inspected) return;
  assert(inspected.Config?.Labels?.[runLabel] === runId, `refusing to remove unowned container ${name}`);
  const result = spawnBounded("docker", ["rm", "--volumes", "--force", name], {
    timeoutMilliseconds: 15_000,
  });
  if (result.status !== 0 && !isNoSuchContainer(result)) {
    throw new Error(`failed to remove ${name}: ${(result.stderr || result.stdout).trim()}`);
  }
  ownedContainers.delete(name);
}

function assertNoOwnedContainers() {
  const output = runChecked(
    "docker",
    ["ps", "--all", "--filter", `label=${runLabel}=${runId}`, "--format", "{{.ID}}"],
    "Disposable container absence check",
    { timeoutMilliseconds: 5_000 },
  );
  assert(outputLines(output).length === 0, `owned disposable containers remain: ${output}`);
}

function inspectContainerForCleanup(reference) {
  const result = spawnBounded("docker", ["inspect", reference], { timeoutMilliseconds: 5_000 });
  if (result.status === 0) {
    const parsed = JSON.parse(result.stdout);
    assert(Array.isArray(parsed) && parsed.length === 1, `invalid Docker inspect response for ${reference}`);
    return parsed[0];
  }
  if (isNoSuchContainer(result)) return null;
  throw new Error(`Docker inspect failed for ${reference}: ${(result.stderr || result.stdout).trim()}`);
}

function isNoSuchContainer(result) {
  return /no such (?:object|container)/iu.test(`${result.stderr ?? ""}\n${result.stdout ?? ""}`);
}

function assertDeepEqual(actual, expected, label) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  assert(actualJson === expectedJson, `${label} mismatch\nexpected=${expectedJson}\nactual=${actualJson}`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function abortableDelay(milliseconds) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds);
    abortController.signal.addEventListener("abort", () => {
      clearTimeout(timer);
      reject(abortController.signal.reason ?? new Error("acceptance aborted"));
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
