[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v[1-9][0-9]*$')]
    [string] $Version
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$outputDirectory = Join-Path $root "infra/local/bootstrap/$Version"
$utf8 = New-Object System.Text.UTF8Encoding($false)
# Deliberate public-content allowlist. Never export operational/customer tables.
$tables = @("assets", "restaurants", "menu_versions", "menu_categories", "menu_items",
    "menu_modifier_groups", "menu_modifier_options", "site_configuration", "site_branding",
    "site_profile", "site_features")

function Invoke-Compose {
    param([string[]] $Arguments)
    & docker @script:compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker export command failed (exit $LASTEXITCODE)." }
}

Push-Location $root
try {
    if (Test-Path -LiteralPath $outputDirectory) {
        throw "Bootstrap version already exists: $Version. Choose a new version; review its public content before committing."
    }
    $script:compose = @("compose", "--project-directory", $root, "--file", (Join-Path $root "compose.yaml"))
    if (Test-Path -LiteralPath (Join-Path $root ".env")) {
        $script:compose += @("--env-file", (Join-Path $root ".env"))
    }
    $script:compose += @("--profile", "assets", "--profile", "app")
    $composeJson = Invoke-Compose -Arguments @("config", "--format", "json")
    $model = ($composeJson -join "`n") | ConvertFrom-Json
    $databaseUser = $model.services.postgres.environment.POSTGRES_USER
    $databaseName = $model.services.postgres.environment.POSTGRES_DB
    $queryPrefix = @("exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", $databaseUser, "-d", $databaseName, "-At", "-c")
    $schemaVersion = Invoke-Compose -Arguments ($queryPrefix + @("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"))
    if ("$schemaVersion".Trim() -ne "9") { throw "This bootstrap exporter currently supports Flyway V9 only." }
    $assetJson = Invoke-Compose -Arguments ($queryPrefix + @("SELECT COALESCE(json_agg(a ORDER BY object_key), '[]') FROM (SELECT object_key, sha256, size_bytes, content_type FROM assets) a"))
    $assets = ($assetJson -join "`n") | ConvertFrom-Json
    foreach ($asset in $assets) {
        if ($asset.object_key -notmatch '^assets/[a-z][a-z0-9]*(?:_[a-z0-9]+)*/[0-9a-f]{64}\.(png|jpg|webp|ico)$' `
                -or $asset.sha256 -notmatch '^[0-9a-f]{64}$' `
                -or -not $asset.object_key.Contains($asset.sha256)) {
            throw "Asset has a noncanonical object key or hash."
        }
    }
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
    $apiPort = $model.services.api.ports[0].published
    $assetBase = $model.services.api.environment.ASSET_BASE_URL.TrimEnd("/")
    foreach ($resource in @("config", "menu")) {
        $response = Invoke-WebRequest -Uri "http://localhost:$apiPort/api/v1/public/$resource" -UseBasicParsing
        # Spring JSON is UTF-8; Windows PowerShell 5.1 otherwise defaults to Latin-1
        # when application/json has no explicit charset.
        $jsonText = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
        $expectedJson = $jsonText.Replace($assetBase, "{{ASSET_BASE_URL}}")
        [IO.File]::WriteAllText((Join-Path $outputDirectory "public-$resource.json"), $expectedJson + "`n", $utf8)
    }
    $dumpArguments = @($tables | ForEach-Object { "--table=public.$_" })
    Invoke-Compose -Arguments (@("exec", "-T", "postgres", "pg_dump", "-U", $databaseUser, "-d", $databaseName, "--data-only", "--column-inserts", "--no-owner", "--no-privileges", "--no-comments", "--file=/tmp/emenu-bootstrap.sql") + $dumpArguments)
    Invoke-Compose -Arguments @("cp", "postgres:/tmp/emenu-bootstrap.sql", (Join-Path $outputDirectory "content.sql"))
    $sqlPath = Join-Path $outputDirectory "content.sql"
    $sql = [IO.File]::ReadAllText($sqlPath).Replace("`r`n", "`n")
    # Newer pg_dump versions may add a per-export random psql restriction token.
    $sql = [regex]::Replace($sql, '(?m)^\\(?:un)?restrict .*\n', '')
    $sql = $sql.TrimEnd() + "`n"
    [IO.File]::WriteAllText($sqlPath, $sql, $utf8)

    $keysFile = Join-Path $outputDirectory "object-keys.txt"
    [IO.File]::WriteAllText($keysFile, (($assets | ForEach-Object object_key) -join "`n") + "`n", $utf8)
    $copyObjects = 'mc alias set source http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; while IFS= read -r key; do [ -n "$key" ] || continue; mkdir -p "/export/$(dirname "$key")"; mc cp "source/$MINIO_BUCKET/$key" "/export/$key"; done < /export/object-keys.txt'
    # Encode script text to avoid Windows PowerShell 5.1 stripping embedded quotes.
    # This is fixed shell code; credentials stay in the container environment.
    $copyEncoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("set -eu`n" + $copyObjects))
    Invoke-Compose -Arguments @("run", "--rm", "--no-deps", "--volume", "${outputDirectory}:/export", "--entrypoint", "/bin/sh", "minio-init", "-ec", "echo $copyEncoded | base64 -d | sh")
    Remove-Item -LiteralPath $keysFile

    $rowCounts = [ordered]@{}
    foreach ($table in $tables) {
        $rowCounts[$table] = @([regex]::Matches($sql, "(?m)^INSERT INTO public\.$table ")).Count
    }
    foreach ($asset in $assets) {
        $localFile = Join-Path $outputDirectory $asset.object_key
        if ((Get-FileHash -LiteralPath $localFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $asset.sha256 `
                -or (Get-Item -LiteralPath $localFile).Length -ne $asset.size_bytes) {
            throw "Exported asset integrity check failed."
        }
    }
    $publicDirectory = Join-Path $root "apps/web/public"
    $frontendAssets = @(Get-ChildItem -LiteralPath $publicDirectory -Recurse -File | Sort-Object FullName | ForEach-Object {
        [ordered]@{
            path = $_.FullName.Substring($publicDirectory.Length + 1).Replace("\", "/")
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            sizeBytes = $_.Length
        }
    })
    $manifest = [ordered]@{
        schemaVersion = 1
        version = $Version
        requiredFlywayVersion = "9"
        purpose = "Public restaurant configuration and menu for fresh local Docker installations"
        tables = $rowCounts
        assets = @($assets)
        frontendAssetsDirectory = "apps/web/public"
        frontendAssets = $frontendAssets
    }
    [IO.File]::WriteAllText((Join-Path $outputDirectory "manifest.json"), ($manifest | ConvertTo-Json -Depth 8).Replace("`r`n", "`n") + "`n", $utf8)
    $checksums = @("content.sql", "manifest.json", "public-config.json", "public-menu.json") + @($assets | ForEach-Object object_key)
    $checksumLines = foreach ($relativeFile in $checksums) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $outputDirectory $relativeFile) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relativeFile"
    }
    [IO.File]::WriteAllText((Join-Path $outputDirectory "checksums.sha256"), ($checksumLines -join "`n") + "`n", $utf8)
    Write-Host "Exported $Version. Review content.sql, manifest.json and images before adding them to Git."
} finally {
    Pop-Location
}
