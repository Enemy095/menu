[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot "ngrok-common.ps1")
try {
    $managed = Get-ManagedNgrok
    if ($null -ne $managed) {
        Stop-Process -Id $managed.Process.Id
        $managed.Process.WaitForExit(10000) | Out-Null
    }
    Remove-Item -LiteralPath $stateFile -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $runtime "public-url.txt") -Force -ErrorAction SilentlyContinue
    Write-Host "Public tunnel stopped. Docker and application data remain running."
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
