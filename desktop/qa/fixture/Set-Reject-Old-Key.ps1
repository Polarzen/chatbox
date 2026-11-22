[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RunRoot,
    [Parameter(Mandatory = $true)]
    [bool]$Reject
)

$ErrorActionPreference = 'Stop'
$resolvedRunRoot = (Resolve-Path -LiteralPath $RunRoot).Path
$controlsFile = Join-Path $resolvedRunRoot 'private\controls.properties'
$value = if ($Reject) { 'true' } else { 'false' }
[IO.File]::WriteAllText($controlsFile, ('reject.original=' + $value + [Environment]::NewLine), [Text.Encoding]::ASCII)
Write-Output ('Previously-valid old key rejection: ' + $value)
