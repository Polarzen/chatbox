[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$FixtureConfigPath,
    [Parameter(Mandatory = $true)][string]$FixtureLogPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command 'java.exe' -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) { throw 'java.exe is missing; set JAVA_HOME to a supported JDK.' }
$jdk = Split-Path (Split-Path $javaExe -Parent) -Parent
$versionOutput = & $javaExe -version 2>&1 | Out-String
if ($versionOutput -notmatch 'version "17\.') { throw 'Desktop UI acceptance requires JDK 17. Set JAVA_HOME to JDK 17 before running.' }
if (-not (Test-Path -LiteralPath $FixtureConfigPath -PathType Leaf)) { throw 'Fixture properties file is missing.' }
if (-not (Test-Path -LiteralPath $FixtureLogPath -PathType Leaf)) { throw 'Fixture service log is missing.' }

$oldJavaHome = $env:JAVA_HOME
$oldFixtureConfig = $env:CHATBOX_UI_FIXTURE_CONFIG
$oldFixtureLog = $env:CHATBOX_UI_FIXTURE_LOG
try {
    $env:JAVA_HOME = $jdk
    $env:CHATBOX_UI_FIXTURE_CONFIG = (Resolve-Path -LiteralPath $FixtureConfigPath).Path
    $env:CHATBOX_UI_FIXTURE_LOG = (Resolve-Path -LiteralPath $FixtureLogPath).Path
    $gradle = Join-Path $repoRoot 'gradlew.bat'
    $gradleArgs = @(
        '-p', (Join-Path $repoRoot 'desktop'),
        '--offline',
        'test',
        '--tests', 'dev.polarzen.chatbox.DesktopUiAcceptanceTest'
    )
    & $gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Desktop UI acceptance B-E failed.' }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:CHATBOX_UI_FIXTURE_CONFIG = $oldFixtureConfig
    $env:CHATBOX_UI_FIXTURE_LOG = $oldFixtureLog
}
