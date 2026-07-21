[CmdletBinding()]
param(
    [switch] $SkipDatabase,
    [string] $RecipientUsername
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $root ".env.telegram.local"
$exampleFile = Join-Path $root ".env.telegram.local.example"

. (Join-Path $PSScriptRoot "telegram-env.ps1")

if (-not (Test-Path -LiteralPath $environmentFile)) {
    Copy-Item -LiteralPath $exampleFile -Destination $environmentFile
    Write-Host "Created $environmentFile"
    Write-Host "Paste the BotFather token into TELEGRAM_BOT_TOKEN, send /start to the bot, then run this script again."
    exit 2
}

Import-TelegramEnvironment -Path $environmentFile

if ([string]::IsNullOrWhiteSpace($env:TELEGRAM_BOT_TOKEN) `
        -or $env:TELEGRAM_BOT_TOKEN -eq "PASTE_BOTFATHER_TOKEN_HERE") {
    throw "Paste the BotFather token into TELEGRAM_BOT_TOKEN in .env.telegram.local"
}

$apiRoot = "https://api.telegram.org/bot$($env:TELEGRAM_BOT_TOKEN)"

try {
    $identity = Invoke-RestMethod -Method Get -Uri "$apiRoot/getMe" -TimeoutSec 20
} catch {
    throw "Telegram rejected the bot token or is temporarily unavailable. The token was not printed."
}
if (-not $identity.ok) {
    throw "Telegram bot identity check failed"
}
Write-Host ("Bot verified: @" + $identity.result.username)

if (-not [string]::IsNullOrWhiteSpace($RecipientUsername)) {
    $RecipientUsername = $RecipientUsername.Trim().TrimStart("@")
    if ($RecipientUsername -notmatch "^[A-Za-z0-9_]{5,32}$") {
        throw "RecipientUsername must be a valid Telegram username"
    }
}

if ([string]::IsNullOrWhiteSpace($env:TELEGRAM_CHAT_ID) `
        -or -not [string]::IsNullOrWhiteSpace($RecipientUsername)) {
    $webhook = Invoke-RestMethod -Method Get -Uri "$apiRoot/getWebhookInfo" -TimeoutSec 20
    if ($webhook.ok -and -not [string]::IsNullOrWhiteSpace([string]$webhook.result.url)) {
        throw "This bot has an active webhook. Remove it with BotFather/provider tooling before local getUpdates discovery."
    }

    $updates = Invoke-RestMethod -Method Get -Uri "$apiRoot/getUpdates?timeout=0&allowed_updates=%5B%22message%22%5D" -TimeoutSec 20
    $privateStarts = @(
        $updates.result |
            Where-Object {
                $_.message `
                    -and $_.message.chat.type -eq "private" `
                    -and ([string]$_.message.text).StartsWith("/start")
            }
    )
    if (-not [string]::IsNullOrWhiteSpace($RecipientUsername)) {
        $privateStarts = @(
            $privateStarts |
                Where-Object {
                    ([string]$_.message.chat.username).Equals(
                        $RecipientUsername,
                        [System.StringComparison]::OrdinalIgnoreCase)
                }
        )
    }
    if ($privateStarts.Count -eq 0) {
        $recipientHint = if ([string]::IsNullOrWhiteSpace($RecipientUsername)) {
            ""
        } else {
            " from @$RecipientUsername"
        }
        throw "Open @$($identity.result.username) in Telegram$recipientHint, press Start or send /start, then run this script again."
    }

    $chatIds = @($privateStarts | ForEach-Object { [string]$_.message.chat.id } | Select-Object -Unique)
    if ($chatIds.Count -ne 1) {
        throw "More than one private /start chat was found. Put the intended numeric chat ID into TELEGRAM_CHAT_ID manually."
    }
    $env:TELEGRAM_CHAT_ID = $chatIds[0]
    Set-TelegramEnvironmentValue -Path $environmentFile -Name "TELEGRAM_CHAT_ID" -Value $env:TELEGRAM_CHAT_ID
    Write-Host "Private recipient chat discovered and saved (value hidden)."
}

$testPayload = @{
    chat_id = $env:TELEGRAM_CHAT_ID
    text = "✅ Бот «У Ризвана» подключён. Новые заказы будут приходить в этот чат."
} | ConvertTo-Json -Compress
try {
    $testResult = Invoke-RestMethod `
        -Method Post `
        -Uri "$apiRoot/sendMessage" `
        -ContentType "application/json; charset=utf-8" `
        -Body $testPayload `
        -TimeoutSec 20
} catch {
    throw "Telegram test message failed. Check that the bot is not blocked and the chat ID is correct."
}
if (-not $testResult.ok) {
    throw "Telegram test message was not accepted"
}
Write-Host "Telegram test message delivered."

if (-not $SkipDatabase) {
    Push-Location $root
    try {
        $postgresId = docker compose ps -q postgres
        if ([string]::IsNullOrWhiteSpace($postgresId)) {
            throw "PostgreSQL is not running. Run: docker compose up -d --wait postgres"
        }

        $reference = if ([string]::IsNullOrWhiteSpace($env:TELEGRAM_DESTINATION_SECRET_REFERENCE)) {
            "secret://application/telegram/orders"
        } else {
            $env:TELEGRAM_DESTINATION_SECRET_REFERENCE
        }
        if ($reference -notmatch "^secret://[A-Za-z0-9.-]+/[A-Za-z0-9._/-]+$") {
            throw "TELEGRAM_DESTINATION_SECRET_REFERENCE is invalid"
        }

        $sql = @"
UPDATE notification_destinations
   SET status = 'disabled', updated_at = now()
 WHERE status = 'active'
   AND channel <> 'telegram';

INSERT INTO notification_destinations (
    id, channel, status, display_name, secret_reference, metadata, created_at, updated_at
) VALUES (
    gen_random_uuid(), 'telegram', 'active', 'restaurant-orders',
    '$reference', '{}'::jsonb, now(), now()
)
ON CONFLICT (channel, display_name) DO UPDATE
   SET status = 'active',
       secret_reference = EXCLUDED.secret_reference,
       updated_at = now();
"@
        $sql | docker compose exec -T postgres `
            sh -ec 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -'
        if ($LASTEXITCODE -ne 0) {
            throw "Telegram destination database provisioning failed"
        }
        Write-Host "Telegram destination registered in PostgreSQL."
    } finally {
        Pop-Location
    }
}

Write-Host ""
Write-Host "Setup complete. Start the API with:"
Write-Host "  .\scripts\start-api-with-telegram.ps1"
