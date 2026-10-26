Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $root ".runtime/ngrok"
$stateFile = Join-Path $runtime "state.json"

function Get-ManagedNgrok {
    if (-not (Test-Path -LiteralPath $stateFile)) { return $null }
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    if ($state.root -ne $root) { throw "Ngrok state belongs to another checkout." }
    $process = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    if ($process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$state.started).ToUniversalTime().Ticks -or $process.Path -ne $state.executable) {
        throw "Saved PID belongs to another process; it will not be stopped or reused."
    }
    return [pscustomobject]@{ Process = $process; State = $state }
}

function Get-PublicTunnel([int] $Port, [string] $Upstream) {
    try {
        $response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/tunnels" -TimeoutSec 3
        return $response.tunnels | Where-Object {
            $_.public_url -match '^https://[a-zA-Z0-9.-]+$' -and $_.config.addr -eq $Upstream
        } | Select-Object -First 1
    } catch { return $null }
}
