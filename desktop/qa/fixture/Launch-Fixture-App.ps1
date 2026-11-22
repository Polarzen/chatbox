[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RunRoot
)

$ErrorActionPreference = 'Stop'
$resolvedRunRoot = (Resolve-Path -LiteralPath $RunRoot).Path
$privateProps = Join-Path $resolvedRunRoot 'private\fixture-private.properties'
if (-not (Test-Path -LiteralPath $privateProps -PathType Leaf)) { throw 'Fixture private configuration is missing.' }

$properties = @{}
foreach ($line in [IO.File]::ReadAllLines($privateProps, [Text.Encoding]::UTF8)) {
    if ($line -match '^([^=]+)=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
}
$localAppData = Join-Path $resolvedRunRoot 'localappdata'
if (-not (Test-Path -LiteralPath $localAppData -PathType Container)) { throw 'Fixture app data directory is missing.' }
$trustStore = [IO.Path]::GetFullPath($properties['tls.trustStore'])
if (-not (Test-Path -LiteralPath $trustStore -PathType Leaf)) { throw 'Fixture public-certificate truststore is missing.' }

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$gradlew = Join-Path $repoRoot 'gradlew.bat'
$initScript = Join-Path $PSScriptRoot 'fixture-run.init.gradle'
$jdkFallback = Join-Path $repoRoot '..\temp\windows-build-20261122\jdk-17.0.21.1+2'
$oldJavaHome = $env:JAVA_HOME
$oldLocalAppData = $env:LOCALAPPDATA
$oldFixtureTrustStore = $env:CHATBOX_FIXTURE_TRUSTSTORE
$pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
$pathJavaHome = if ($pathJava) {
    Split-Path -Parent (Split-Path -Parent $pathJava.Source)
} else {
    $null
}

function Test-GradleJava([string]$JavaHome) {
    $java = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { return $false }
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $version = (& $java -version 2>&1 | Out-String) }
    finally { $ErrorActionPreference = $previousPreference }
    return $version -match 'version "(17|21)\.'
}

$javaHome = @($oldJavaHome, $jdkFallback, $pathJavaHome) |
    Where-Object { $_ -and (Test-GradleJava $_) } |
    Select-Object -First 1
if (-not $javaHome) { throw 'A compatible JDK 17 or 21 is required to launch the desktop app.' }
$javaHome = (Resolve-Path -LiteralPath $javaHome).Path

try {
    $env:JAVA_HOME = $javaHome
    $env:LOCALAPPDATA = $localAppData
    $env:CHATBOX_FIXTURE_TRUSTSTORE = $trustStore
    & $gradlew '-p' (Join-Path $repoRoot 'desktop') '-I' $initScript 'run'
    if ($LASTEXITCODE -ne 0) { throw 'The desktop app exited with an error.' }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:LOCALAPPDATA = $oldLocalAppData
    $env:CHATBOX_FIXTURE_TRUSTSTORE = $oldFixtureTrustStore
}
