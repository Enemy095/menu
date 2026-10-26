Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Import-TelegramEnvironment {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Telegram environment file does not exist: $Path"
    }

    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) {
            continue
        }

        $parts = $line -split "=", 2
        if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0])) {
            throw "Invalid environment line in $Path"
        }
        $name = $parts[0].Trim()
        $value = $parts[1].Trim()
        if ($name -notmatch "^[A-Z][A-Z0-9_]*$") {
            throw "Invalid environment variable name in $Path"
        }
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Set-TelegramEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [string] $Name,
        [Parameter(Mandatory = $true)]
        [string] $Value
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    $found = $false
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match ("^" + [regex]::Escape($Name) + "=")) {
            $lines.Add("$Name=$Value")
            $found = $true
        } else {
            $lines.Add($line)
        }
    }
    if (-not $found) {
        $lines.Add("$Name=$Value")
    }
    Set-Content -LiteralPath $Path -Value $lines -Encoding utf8NoBOM
}
