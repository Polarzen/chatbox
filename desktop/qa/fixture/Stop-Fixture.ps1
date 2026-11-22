[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RunRoot
)

$ErrorActionPreference = 'Stop'
$resolvedRunRoot = (Resolve-Path -LiteralPath $RunRoot).Path
$pidFile = Join-Path $resolvedRunRoot 'private\service.pid'
$privateProps = Join-Path $resolvedRunRoot 'private\fixture-private.properties'
if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) {
    Write-Output 'Fixture PID file is missing; no process stopped.'
    return
}
$serviceId = [int][IO.File]::ReadAllText($pidFile).Trim()
$process = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $serviceId) -ErrorAction SilentlyContinue
if ($process -and $process.CommandLine.Contains('FixtureServer') -and $process.CommandLine.Contains($privateProps)) {
    Stop-Process -Id $serviceId -Force
    Write-Output 'Stopped the local fixture service.'
} else {
    Write-Output 'The recorded fixture process is already stopped.'
}
