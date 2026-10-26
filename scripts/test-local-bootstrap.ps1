[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$project = "emenu-bootstrap-test-" + [Guid]::NewGuid().ToString("N").Substring(0, 10)
$testDirectory = Join-Path $root ".runtime/$project"
$utf8 = New-Object System.Text.UTF8Encoding($false)
$script:testCompose = @()
$cleanupAllowed = $false

function Invoke-TestCompose {
    param([string[]] $Arguments)
    & docker @script:testCompose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Bootstrap test Docker command failed (exit $LASTEXITCODE)." }
}

function Get-FreePort {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Invoke-TestObjectScript {
    param([string] $Body)
    $setup = 'set -eu; mc alias set destination http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; '
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($setup + $Body))
    Invoke-TestCompose -Arguments @("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "bootstrap", "-ec", "echo $encoded | base64 -d | sh")
}

function Assert-BootstrapFailure {
    param([string[]] $ExtraArguments = @(), [string] $ExpectedMessage)
    $ErrorActionPreference = "Continue"
    $output = & docker @script:testCompose run --rm --no-deps @ExtraArguments bootstrap 2>&1
    $failureExit = $LASTEXITCODE
    if ($failureExit -eq 0 -or ($output | Out-String) -notmatch $ExpectedMessage) {
        throw "Bootstrap did not fail with the expected reason: $ExpectedMessage"
    }
    Write-Host "Expected failure verified: $ExpectedMessage"
}

Push-Location $root
try {
    New-Item -ItemType Directory -Path $testDirectory -Force | Out-Null
    $webPort = Get-FreePort
    $apiPort = Get-FreePort
    $assetPort = Get-FreePort
    $environmentPath = Join-Path $testDirectory "test.env"
    $testEnvironment = @(
        "COMPOSE_PROJECT_NAME=$project", "WEB_PORT=$webPort", "API_PORT=$apiPort",
        "POSTGRES_PORT=$(Get-FreePort)", "MINIO_API_PORT=$assetPort", "MINIO_CONSOLE_PORT=$(Get-FreePort)",
        "ASSET_BASE_URL=http://localhost:$assetPort/menu-assets-local"
    ) -join "`n"
    [IO.File]::WriteAllText($environmentPath, $testEnvironment + "`n", $utf8)
    $overridePath = Join-Path $testDirectory "compose.test.yaml"
    $override = @"
services:
  api:
    image: emenu-local-api:latest
    environment:
      APP_WORKER_ENABLED: "false"
      TELEGRAM_NOTIFICATIONS_ENABLED: "false"
      TELEGRAM_BOT_TOKEN: ""
      TELEGRAM_CHAT_ID: ""
  web:
    image: emenu-local-web:latest
  bootstrap:
    image: emenu-local-bootstrap:latest
    environment:
      LOCAL_BOOTSTRAP_ENABLED: "true"
networks:
  backend:
    name: $project-backend
  assets:
    name: $project-assets
volumes:
  postgres-data:
    name: $project-postgres-data
  minio-data:
    name: $project-minio-data
  prometheus-data:
    name: $project-prometheus-data
"@
    [IO.File]::WriteAllText($overridePath, $override + "`n", $utf8)
    $script:testCompose = @("compose", "--project-name", $project, "--project-directory", $root,
        "--file", (Join-Path $root "compose.yaml"), "--file", $overridePath,
        "--env-file", (Join-Path $root ".env.example"), "--env-file", $environmentPath,
        "--profile", "app", "--profile", "assets")
    $resolved = Invoke-TestCompose -Arguments @("config", "--format", "json")
    $model = ($resolved -join "`n") | ConvertFrom-Json
    foreach ($volume in $model.volumes.PSObject.Properties) {
        if ($volume.Value.name -ne "$project-$($volume.Name)") { throw "Unsafe test volume name." }
    }
    if ($model.name -ne $project) { throw "Unsafe test project name." }
    $cleanupAllowed = $true
    $manifest = Get-Content -LiteralPath (Join-Path $root "infra/local/bootstrap/v1/manifest.json") -Raw | ConvertFrom-Json
    $queryPrefix = @("exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", $model.services.postgres.environment.POSTGRES_USER,
        "-d", $model.services.postgres.environment.POSTGRES_DB, "-At", "-c")
    Invoke-TestCompose -Arguments @("up", "--no-build", "--detach", "--wait", "--wait-timeout", "180", "api")

    $tamperedDirectory = Join-Path $testDirectory "tampered-v1"
    Copy-Item -LiteralPath (Join-Path $root "infra/local/bootstrap/v1") -Destination $tamperedDirectory -Recurse
    [IO.File]::AppendAllText((Join-Path $tamperedDirectory "content.sql"), "`n-- tampered test snapshot`n", $utf8)
    Assert-BootstrapFailure -ExtraArguments @("--volume", "${tamperedDirectory}:/bootstrap/v1:ro") -ExpectedMessage "FAILED"

    $objectKey = $manifest.assets[0].object_key
    Invoke-TestObjectScript -Body ('printf invalid-test-object | mc pipe --quiet "destination/$MINIO_BUCKET/' + $objectKey + '"')
    Assert-BootstrapFailure -ExpectedMessage "LOCAL_BOOTSTRAP_ASSET_CONFLICT"
    $rows = Invoke-TestCompose -Arguments ($queryPrefix + @("SELECT count(*) FROM menu_items"))
    if ([int] "$rows" -ne 0) { throw "Asset conflict wrote database content." }
    Invoke-TestObjectScript -Body ('mc rm --force "destination/$MINIO_BUCKET/' + $objectKey + '"')

    # A schema mismatch may upload the immutable file, but must publish no DB rows.
    Invoke-TestCompose -Arguments ($queryPrefix + @("UPDATE flyway_schema_history SET version = '99' WHERE version = '9'"))
    Assert-BootstrapFailure -ExpectedMessage "LOCAL_BOOTSTRAP_SCHEMA_MISMATCH"
    $rows = Invoke-TestCompose -Arguments ($queryPrefix + @("SELECT count(*) FROM assets"))
    if ([int] "$rows" -ne 0) { throw "Schema mismatch wrote database content." }
    Invoke-TestCompose -Arguments ($queryPrefix + @("UPDATE flyway_schema_history SET version = '9' WHERE version = '99'"))

    Invoke-TestCompose -Arguments @("up", "--no-build", "--detach", "--wait", "--wait-timeout", "180")

    $menu = Invoke-RestMethod -Uri "http://localhost:$apiPort/api/v1/public/menu"
    if ($menu.items.Count -ne $manifest.tables.menu_items -or $menu.categories.Count -ne $manifest.tables.menu_categories) {
        throw "Seeded public menu counts differ from the snapshot."
    }
    $web = Invoke-WebRequest -Uri "http://localhost:$webPort/" -UseBasicParsing
    if ($web.StatusCode -ne 200) { throw "Fresh frontend did not return HTTP 200." }
    foreach ($resource in @("config", "menu")) {
        $response = Invoke-WebRequest -Uri "http://localhost:$apiPort/api/v1/public/$resource" -UseBasicParsing
        $actual = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()).Replace("http://localhost:$assetPort/menu-assets-local", "{{ASSET_BASE_URL}}")
        $expected = Get-Content -LiteralPath (Join-Path $root "infra/local/bootstrap/v1/public-$resource.json") -Raw -Encoding UTF8
        if ($actual.Trim() -ne $expected.Trim()) { throw "Fresh public $resource differs from the exported source response." }
    }

    foreach ($table in $manifest.tables.PSObject.Properties) {
        $count = Invoke-TestCompose -Arguments ($queryPrefix + @("SELECT count(*) FROM public.$($table.Name)"))
        if ([int] "$count" -ne $table.Value) { throw "Wrong seeded row count in $($table.Name)." }
    }
    $privateRows = Invoke-TestCompose -Arguments ($queryPrefix + @("SELECT (SELECT count(*) FROM orders) + (SELECT count(*) FROM notification_destinations) + (SELECT count(*) FROM outbox_events)"))
    if ([int] "$privateRows" -ne 0) { throw "Operational data leaked into the bootstrap." }
    foreach ($asset in $manifest.assets) {
        $download = Join-Path $testDirectory "download.bin"
        Invoke-WebRequest -Uri "http://localhost:$assetPort/menu-assets-local/$($asset.object_key)" -OutFile $download -UseBasicParsing
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -ne $asset.sha256) {
            throw "Restored object storage image differs."
        }
    }
    foreach ($asset in $manifest.frontendAssets) {
        $download = Join-Path $testDirectory "download.bin"
        Invoke-WebRequest -Uri "http://localhost:$webPort/$($asset.path)" -OutFile $download -UseBasicParsing
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -ne $asset.sha256) {
            throw "Frontend asset differs from the snapshot: $($asset.path)"
        }
    }

    # A local edit must survive subsequent startup. This touches only the disposable database.
    Invoke-TestCompose -Arguments ($queryPrefix + @("UPDATE menu_items SET price_minor = price_minor + 1"))
    $totalBefore = Invoke-TestCompose -Arguments ($queryPrefix + @("SELECT sum(price_minor) FROM menu_items"))
    $replay = Invoke-TestCompose -Arguments @("run", "--rm", "--no-deps", "bootstrap")
    if (($replay -join "`n") -notmatch "LOCAL_BOOTSTRAP_SKIPPED") { throw "Existing data was not skipped." }
    $totalAfter = Invoke-TestCompose -Arguments ($queryPrefix + @("SELECT sum(price_minor) FROM menu_items"))
    if ("$totalBefore" -ne "$totalAfter") { throw "Bootstrap overwrote a local content edit." }
    Write-Host "Bootstrap acceptance passed: fresh Flyway schema, complete content graph, matching images/fonts, no operational data, safe replay."
} catch {
    if ($cleanupAllowed) { & docker @script:testCompose logs --tail 40 bootstrap }
    throw
} finally {
    if ($cleanupAllowed) {
        # This exact Compose model uses only the generated, verified test volume names.
        & docker @script:testCompose down --volumes --remove-orphans
        if ($LASTEXITCODE -ne 0) { Write-Warning "Test cleanup failed for $project. Inspect its isolated containers/volumes." }
    }
    Pop-Location
}
