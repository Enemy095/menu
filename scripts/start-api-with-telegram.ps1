Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $root ".env.telegram.local"

. (Join-Path $PSScriptRoot "telegram-env.ps1")
Import-TelegramEnvironment -Path $environmentFile

if ([string]::IsNullOrWhiteSpace($env:TELEGRAM_BOT_TOKEN) `
        -or [string]::IsNullOrWhiteSpace($env:TELEGRAM_CHAT_ID)) {
    throw "Run .\scripts\setup-telegram-local.ps1 first"
}

$env:SPRING_PROFILES_ACTIVE = "local"
$env:TELEGRAM_NOTIFICATIONS_ENABLED = "true"
$env:APP_WORKER_ENABLED = "true"

Push-Location $root
try {
    & .\gradlew.bat :apps:api:bootRun
    if ($LASTEXITCODE -ne 0) {
        throw "Spring API stopped with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
