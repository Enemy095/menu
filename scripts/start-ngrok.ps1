[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)] [int] $InspectorPort = 4047,
    [ValidateRange(30, 1800)] [int] $WaitTimeoutSeconds = 180
)
. (Join-Path $PSScriptRoot "ngrok-common.ps1")

try {
    $binary = (Get-Command ngrok -ErrorAction Stop).Source
    $baseConfig = Join-Path $env:LOCALAPPDATA "ngrok/ngrok.yml"
    if (-not (Test-Path -LiteralPath $baseConfig)) {
        throw "Configure your account first: ngrok config add-authtoken YOUR_TOKEN (https://dashboard.ngrok.com/get-started/your-authtoken)."
    }
    & $binary config check --config $baseConfig
    if ($LASTEXITCODE -ne 0) { throw "Ngrok configuration is invalid." }
    $webPort = "3000"
    foreach ($file in @(".env", ".env.telegram.local")) {
        $filePath = Join-Path $root $file
        if (Test-Path -LiteralPath $filePath) {
            foreach ($line in Get-Content -LiteralPath $filePath) {
                if ($line -match '^\s*WEB_PORT\s*=\s*["'']?(\d+)["'']?\s*$') { $webPort = $Matches[1] }
            }
        }
    }
    if ($env:WEB_PORT) { $webPort = $env:WEB_PORT }
    $portNumber = 0
    if (-not [int]::TryParse($webPort, [ref]$portNumber) -or $portNumber -lt 1 -or $portNumber -gt 65535) { throw "Invalid WEB_PORT." }
    $upstream = "http://127.0.0.1:$webPort"
    New-Item -ItemType Directory -Force -Path $runtime | Out-Null
    $managed = Get-ManagedNgrok
    if ($null -ne $managed -and ($managed.State.upstream -ne $upstream -or $managed.State.inspectorPort -ne $InspectorPort)) {
        throw "Tunnel settings changed. Run stop-ngrok.cmd first."
    }
    if ($null -eq $managed) {
        $occupied = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq $InspectorPort
        if ($occupied) { throw "Inspector port $InspectorPort is occupied by another process. Use -InspectorPort with a free port." }
        $overlay = Join-Path $runtime "agent.yml"
        @('version: "3"', 'agent:', "  web_addr: 127.0.0.1:$InspectorPort") | Set-Content -LiteralPath $overlay -Encoding utf8
        $arguments = @("http", $upstream, "--config", "$baseConfig,$overlay", "--inspect=false", "--log=stdout", "--log-format=json", "--log-level=warn")
        $quoted = $arguments | ForEach-Object { '"' + $_ + '"' }
        $process = Start-Process -FilePath $binary -ArgumentList ($quoted -join ' ') -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $runtime "stdout.log") -RedirectStandardError (Join-Path $runtime "stderr.log")
        $state = [ordered]@{ root = $root; processId = $process.Id; started = $process.StartTime.ToUniversalTime().ToString("o"); executable = $process.Path; inspectorPort = $InspectorPort; upstream = $upstream }
        $state | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding utf8
    }
    $deadline = (Get-Date).AddSeconds(60)
    $tunnel = $null
    do {
        $tunnel = Get-PublicTunnel $InspectorPort $upstream
        if ($null -ne $tunnel) { break }
        $current = Get-ManagedNgrok
        if ($null -eq $current) { throw "Ngrok exited. Check .runtime/ngrok/stderr.log and stdout.log for the account/network error." }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    if ($null -eq $tunnel) { throw "Ngrok did not open a tunnel within 60 seconds. Check its local logs." }
    $publicUrl = $tunnel.public_url
    @("PUBLIC_BASE_URL=$publicUrl", "ASSET_BASE_URL=$publicUrl/media") | Set-Content -LiteralPath (Join-Path $root ".env.ngrok.local") -Encoding ascii
    $previousPublic = $env:PUBLIC_BASE_URL
    $previousAssets = $env:ASSET_BASE_URL
    try {
        $env:PUBLIC_BASE_URL = $publicUrl
        $env:ASSET_BASE_URL = "$publicUrl/media"
        & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "start-docker.ps1") -PublicTunnel -WaitTimeoutSeconds $WaitTimeoutSeconds
        if ($LASTEXITCODE -ne 0) { throw "Application startup failed. Fix the error above and run start-ngrok.cmd again." }
    } finally {
        [Environment]::SetEnvironmentVariable("PUBLIC_BASE_URL", $previousPublic, "Process")
        [Environment]::SetEnvironmentVariable("ASSET_BASE_URL", $previousAssets, "Process")
    }
    $response = Invoke-WebRequest -Uri $publicUrl -UseBasicParsing -TimeoutSec 30 -Headers @{ "ngrok-skip-browser-warning" = "1" }
    if ($response.StatusCode -ne 200) { throw "Public website verification failed." }
    $imagePattern = [regex]::Escape("$publicUrl/media/assets/") + '[a-z-]+/[a-f0-9]{64}\.(png|jpg|jpeg|webp|ico|svg)'
    $images = [regex]::Matches($response.Content, $imagePattern) | ForEach-Object { $_.Value } | Select-Object -Unique
    foreach ($image in $images) {
        $imageResponse = Invoke-WebRequest -Uri $image -UseBasicParsing -TimeoutSec 30 -Headers @{ "ngrok-skip-browser-warning" = "1" }
        if ($imageResponse.StatusCode -ne 200 -or $imageResponse.Headers['Content-Type'] -notmatch '^image/') {
            throw "Public image verification failed."
        }
    }
    $availability = Invoke-RestMethod -Uri "$publicUrl/api/v1/public/availability" -TimeoutSec 30 -Headers @{ "ngrok-skip-browser-warning" = "1" }
    if ($null -eq $availability) { throw "Public schedule verification failed." }
    Set-Content -LiteralPath (Join-Path $runtime "public-url.txt") -Value $publicUrl -Encoding ascii
    Write-Host ""
    Write-Host "Public website: $publicUrl" -ForegroundColor Green
    Write-Host "URL saved in .runtime/ngrok/public-url.txt. You can close this window."
    Write-Host "To disconnect sharing: stop-ngrok.cmd. After reboot: start-ngrok.cmd."
    Write-Host "A free ngrok account may show visitors a Visit Site confirmation first."
} catch {
    Write-Host "Public startup failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
