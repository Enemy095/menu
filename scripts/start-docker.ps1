[CmdletBinding()]
param(
    [ValidateRange(30, 1800)]
    [int] $DockerTimeoutSeconds = 180,
    [ValidateRange(30, 1800)]
    [int] $WaitTimeoutSeconds = 180,
    [switch] $Observability,
    [switch] $PublicTunnel
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Invoke-Docker {
    param([string[]] $DockerArguments)
    & $script:docker @DockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed (exit $LASTEXITCODE)."
    }
}

function Get-DockerEngineType {
    # An unhealthy Desktop can hang even `docker info`; bound every probe as well
    # as the overall wait. No shell is involved in this process invocation.
    $probe = New-Object System.Diagnostics.Process
    $probe.StartInfo.FileName = $script:docker
    $probe.StartInfo.Arguments = 'info --format {{.OSType}}'
    $probe.StartInfo.UseShellExecute = $false
    $probe.StartInfo.CreateNoWindow = $true
    $probe.StartInfo.RedirectStandardOutput = $true
    $probe.StartInfo.RedirectStandardError = $true
    try {
        [void] $probe.Start()
        $stdout = $probe.StandardOutput.ReadToEndAsync()
        $stderr = $probe.StandardError.ReadToEndAsync()
        if (-not $probe.WaitForExit(5000)) {
            $probe.Kill()
            $probe.WaitForExit()
            return ""
        }
        if ($probe.ExitCode -eq 0) { return $stdout.Result.Trim() }
        return ""
    } finally {
        $probe.Dispose()
    }
}

Push-Location $root
try {
    $dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
    if (-not $dockerCommand) {
        $dockerCli = Join-Path $env:ProgramFiles "Docker\Docker\resources\bin\docker.exe"
        if (-not (Test-Path -LiteralPath $dockerCli)) {
            throw "Install Docker Desktop with Linux containers / WSL 2, then run this script again."
        }
        $script:docker = $dockerCli
    } else {
        $script:docker = $dockerCommand.Source
    }

    Write-Host "[1/4] Checking Docker Desktop..."
    $engineType = Get-DockerEngineType
    if (-not $engineType) {
        $desktopPaths = @(
            (Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"),
            (Join-Path $env:LOCALAPPDATA "Programs\DockerDesktop\Docker Desktop.exe")
        )
        $desktop = $desktopPaths | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if (-not $desktop) { throw "Docker engine is unavailable and Docker Desktop.exe was not found. Start Docker Desktop manually." }
        Start-Process -FilePath $desktop -WindowStyle Hidden
        $timer = [System.Diagnostics.Stopwatch]::StartNew()
        while (-not $engineType) {
            if ($timer.Elapsed.TotalSeconds -ge $DockerTimeoutSeconds) {
                throw "Docker Desktop did not become ready in $DockerTimeoutSeconds seconds. Check Docker Desktop / WSL 2 and the selected Docker context."
            }
            Start-Sleep -Seconds 2
            $engineType = Get-DockerEngineType
        }
    }
    if ($engineType -ne "linux") { throw "Switch Docker Desktop to Linux containers and run again." }
    Invoke-Docker -DockerArguments @("compose", "version")

    # Compose loads local configuration itself; never execute .env content or print secrets.
    $environmentFile = Join-Path $root ".env"
    if (-not (Test-Path -LiteralPath $environmentFile)) {
        Copy-Item -LiteralPath (Join-Path $root ".env.example") -Destination $environmentFile
        Write-Host "Created .env with local development defaults."
    }
    $compose = @("compose", "--project-directory", $root, "--file", (Join-Path $root "compose.yaml"), "--env-file", $environmentFile)
    $telegramFile = Join-Path $root ".env.telegram.local"
    if (Test-Path -LiteralPath $telegramFile) {
        $compose += @("--env-file", $telegramFile)
        Write-Host "Using existing local Telegram configuration."
    }
    if ($PublicTunnel) {
        $publicFile = Join-Path $root ".env.ngrok.local"
        if (-not (Test-Path -LiteralPath $publicFile)) { throw "Run start-ngrok.cmd to configure the public tunnel first." }
        $compose += @("--env-file", $publicFile)
    }
    $compose += @("--profile", "assets", "--profile", "app")
    if ($Observability) { $compose += @("--profile", "observability") }

    Write-Host "[2/4] Validating Compose configuration..."
    Invoke-Docker -DockerArguments ($compose + @("config", "--quiet"))

    Write-Host "[3/4] Building images (unchanged layers use Docker cache)..."
    # Build first: a compilation failure leaves the existing application running.
    # Use the Buildx environment control for compatibility with Compose 2.35.
    # Default attestations change image identity on every cached build.
    $previousAttestations = [Environment]::GetEnvironmentVariable("BUILDX_NO_DEFAULT_ATTESTATIONS", "Process")
    try {
        $env:BUILDX_NO_DEFAULT_ATTESTATIONS = "1"
        Invoke-Docker -DockerArguments ($compose + @("build"))
    } finally {
        [Environment]::SetEnvironmentVariable("BUILDX_NO_DEFAULT_ATTESTATIONS", $previousAttestations, "Process")
    }

    Write-Host "[4/4] Starting services and waiting for health checks..."
    try {
        Invoke-Docker -DockerArguments ($compose + @("up", "--detach", "--wait", "--wait-timeout", "$WaitTimeoutSeconds"))
    } catch {
        & $script:docker @compose ps --all
        Write-Host "Inspect service logs with: docker compose --profile app --profile assets logs --tail 100"
        throw
    }
    Invoke-Docker -DockerArguments ($compose + @("ps"))
    $configuration = & $script:docker @compose config --format json
    if ($LASTEXITCODE -ne 0) { throw "Could not read service ports." }
    $model = ($configuration -join "`n") | ConvertFrom-Json
    $telegramSettings = $model.services.api.environment
    if ([string]$telegramSettings.TELEGRAM_NOTIFICATIONS_ENABLED -eq "true") {
        if ([string]::IsNullOrWhiteSpace([string]$telegramSettings.TELEGRAM_BOT_TOKEN) `
                -or [string]::IsNullOrWhiteSpace([string]$telegramSettings.TELEGRAM_CHAT_ID)) {
            throw "Telegram notifications are enabled but TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID is missing."
        }
        $destinationReference = [string]$telegramSettings.TELEGRAM_DESTINATION_SECRET_REFERENCE
        if ($destinationReference -notmatch '^secret://[A-Za-z0-9.-]+/[A-Za-z0-9._/-]+$') {
            throw "TELEGRAM_DESTINATION_SECRET_REFERENCE is invalid."
        }
        # Credentials stay in container environment; only the logical reference enters SQL.
        # A fresh content bootstrap intentionally contains no notification destinations.
        $destinationSql = @"
INSERT INTO notification_destinations (
    id, channel, status, display_name, secret_reference, metadata, created_at, updated_at
) VALUES (
    gen_random_uuid(), 'telegram', 'active', 'restaurant-orders',
    '$destinationReference', '{}'::jsonb, now(), now()
)
ON CONFLICT (channel, display_name) DO UPDATE
   SET status = 'active', secret_reference = EXCLUDED.secret_reference, updated_at = now()
 WHERE notification_destinations.status <> 'active'
    OR notification_destinations.secret_reference <> EXCLUDED.secret_reference;
"@
        $destinationSql | & $script:docker @compose exec -T postgres `
            sh -ec 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -'
        if ($LASTEXITCODE -ne 0) { throw "Telegram destination database provisioning failed." }
        Write-Host "Telegram order destination registered in PostgreSQL."
    }
    $webPort = $model.services.web.ports[0].published
    $apiPort = $model.services.api.ports[0].published
    Write-Host ""
    Write-Host "Application: http://localhost:$webPort"
    Write-Host "API health:  http://localhost:$apiPort/actuator/health/readiness"
    Write-Host "Containers keep running after this window closes. PostgreSQL and MinIO data are retained."
    Write-Host "Fresh databases use the versioned local bootstrap bundle. Existing application data is preserved."
} catch {
    [Console]::Error.WriteLine("Startup failed: " + $_.Exception.Message)
    exit 1
} finally {
    Pop-Location
}
