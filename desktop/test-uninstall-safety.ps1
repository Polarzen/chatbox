[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:OS -ne 'Windows_NT') {
    throw 'The uninstall safety test is restricted to a fresh GitHub-hosted Windows Actions runner.'
}

$runnerTemp = [IO.Path]::GetFullPath($env:RUNNER_TEMP).TrimEnd('\')
$evidenceRoot = [IO.Path]::GetFullPath($EvidenceDirectory).TrimEnd('\')
if (-not $evidenceRoot.StartsWith($runnerTemp + '\chatbox-uninstall-regression-', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Safety-test evidence must be inside the owned uninstall regression directory.'
}
[void] [IO.Directory]::CreateDirectory($evidenceRoot)
$evidenceLog = Join-Path $evidenceRoot 'safety.log'
$fixtureRoot = Join-Path $evidenceRoot 'safety-fixtures'
[void] [IO.Directory]::CreateDirectory($fixtureRoot)

. (Join-Path $PSScriptRoot 'uninstall\Uninstall-ChatBoxData.ps1')

$localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
$userProfile = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
$dataRoot = Join-Path $localAppData 'Chatbox'
$runtimeRoot = Join-Path $localAppData 'Polarzen\Chatbox\device-runtime'
$fallbackRoot = Join-Path $userProfile '.chatbox'
$endpointRoot = Join-Path $userProfile '.polarzen-chatbox-tmp'
$startupPath = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::Startup)) 'Polarzen Chatbox Device Host.lnk'
$ownedRoots = @($dataRoot, $runtimeRoot, $fallbackRoot, $endpointRoot)
foreach ($path in ($ownedRoots + @($startupPath))) {
    if (Test-Path -LiteralPath $path) { throw "Refusing to use pre-existing Chat Box state for safety tests: $path" }
}

function Write-SafetyEvidence([string] $Message) {
    Add-Content -LiteralPath $evidenceLog -Value $Message -Encoding UTF8
    Write-Host $Message
}

function Assert-Safety([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Set-SyntheticFile([string] $Path, [string] $Value) {
    [void] [IO.Directory]::CreateDirectory((Split-Path -Parent $Path))
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Assert-ExpectedFailure([scriptblock] $Action, [string] $Description) {
    $failed = $false
    try { & $Action | Out-Null }
    catch {
        $failed = $true
        Write-SafetyEvidence ($Description + ': refused with ' + $_.Exception.Message)
    }
    if (-not $failed) { throw ($Description + ': unsafe input was not rejected.') }
}

function Remove-SyntheticState {
    $plan = Get-ChatBoxDataPlan
    Assert-ChatBoxDataPlan -Plan $plan
    Stop-ChatBoxLocalServices -Plan $plan
    $null = Remove-ChatBoxLocalData -Plan $plan
    $remaining = @(Test-ChatBoxDataRemoved -Plan $plan)
    Assert-Safety ($remaining.Count -eq 0) ('Synthetic state was not removed: ' + ($remaining -join ', '))
}

function Write-ExternalConfig([string] $Database) {
    Set-SyntheticFile (Join-Path $runtimeRoot 'config.json') (@{
        generation = [Guid]::NewGuid().ToString(); dbPath = $Database; autostart = $false
        mode = 'trusted'; phoneUrl = 'https://example.invalid'; gatewayPort = 18766; ngrokPath = ''
    } | ConvertTo-Json)
}

# A junction at the data root must never make the external target eligible for deletion.
$externalDirectory = Join-Path $fixtureRoot 'outside-junction-target'
$externalSentinel = Join-Path $externalDirectory 'retain.txt'
Set-SyntheticFile $externalSentinel 'outside-data-root'
[void] (New-Item -ItemType Junction -Path $dataRoot -Target $externalDirectory)
try {
    Assert-ExpectedFailure { $plan = Get-ChatBoxDataPlan; Assert-ChatBoxDataPlan -Plan $plan } 'Junction used as the data root'
    Assert-Safety ([IO.File]::ReadAllText($externalSentinel) -ceq 'outside-data-root') 'Root-junction validation touched the external target.'
}
finally {
    # Non-recursive deletion unlinks this exact junction, leaving its target intact.
    [IO.Directory]::Delete($dataRoot)
}

# The same protection must apply inside an otherwise ordinary managed directory.
Set-SyntheticFile (Join-Path $dataRoot 'history.json') 'retain-until-confirmed'
$nestedJunction = Join-Path $dataRoot 'linked-content'
[void] (New-Item -ItemType Junction -Path $nestedJunction -Target $externalDirectory)
try {
    Assert-ExpectedFailure { $plan = Get-ChatBoxDataPlan; Assert-ChatBoxDataPlan -Plan $plan } 'Junction nested inside managed data'
    Assert-Safety ([IO.File]::ReadAllText($externalSentinel) -ceq 'outside-data-root') 'Nested-junction validation touched the external target.'
    Assert-Safety ([IO.File]::ReadAllText((Join-Path $dataRoot 'history.json')) -ceq 'retain-until-confirmed') 'Nested-junction validation removed ordinary data before refusing.'
}
finally { [IO.Directory]::Delete($nestedJunction) }
Remove-SyntheticState

# A matching shortcut filename alone is not sufficient evidence of ownership.
$shell = New-Object -ComObject WScript.Shell
$link = $null
try {
    $link = $shell.CreateShortcut($startupPath)
    $link.TargetPath = Join-Path $env:SystemRoot 'System32\notepad.exe'
    $link.Arguments = $externalSentinel
    $link.WorkingDirectory = $externalDirectory
    $link.Save()
    $before = (Get-FileHash -LiteralPath $startupPath -Algorithm SHA256).Hash
    Assert-ExpectedFailure { $plan = Get-ChatBoxDataPlan; Assert-ChatBoxDataPlan -Plan $plan } 'Foreign shortcut using the Chat Box startup filename'
    Assert-Safety ((Get-FileHash -LiteralPath $startupPath -Algorithm SHA256).Hash -ceq $before) 'The foreign shortcut was changed or removed.'
}
finally {
    if ($null -ne $link) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($link) }
    [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell)
    [IO.File]::Delete($startupPath)
}

# An active data owner or service controller must prevent deletion. These are
# actual Windows byte-range locks, not mocked return values.
$python = Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1
$lockFixtureScript = Join-Path $fixtureRoot 'hold-lock.py'
Set-SyntheticFile $lockFixtureScript @'
import msvcrt
from pathlib import Path
import sys
import time

lock_path, ready_path, release_path = map(Path, sys.argv[1:])
with open(lock_path, "a+b") as stream:
    if lock_path.stat().st_size == 0:
        stream.write(b"\0")
        stream.flush()
    stream.seek(0)
    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
    ready_path.write_text("locked", encoding="ascii")
    try:
        deadline = time.monotonic() + 120
        while not release_path.exists():
            if time.monotonic() > deadline:
                raise RuntimeError("Lock fixture timed out")
            time.sleep(.05)
    finally:
        stream.seek(0)
        msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
'@
foreach ($case in @(
    @{ Name = 'Desktop history lock'; Path = (Join-Path $dataRoot 'history.lock'); Root = $dataRoot },
    @{ Name = 'Fallback history lock'; Path = (Join-Path $fallbackRoot 'history.lock'); Root = $fallbackRoot },
    @{ Name = 'Shared-service controller lock'; Path = (Join-Path $runtimeRoot 'controller.serve.lock'); Root = $runtimeRoot }
)) {
    Set-SyntheticFile (Join-Path $case.Root 'must-survive.txt') 'busy-owner-data'
    $caseId = [Guid]::NewGuid().ToString('N')
    $readyPath = Join-Path $fixtureRoot ($caseId + '-ready.txt')
    $releasePath = Join-Path $fixtureRoot ($caseId + '-release.txt')
    $arguments = '-E -s "' + $lockFixtureScript + '" "' + $case.Path + '" "' + $readyPath + '" "' + $releasePath + '"'
    $holder = Start-Process -FilePath $python.Source -ArgumentList $arguments -PassThru -NoNewWindow
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while (-not (Test-Path -LiteralPath $readyPath)) {
            if ($holder.HasExited) { throw 'The separate lock-holder process exited before acquiring its lock.' }
            if ([DateTime]::UtcNow -ge $deadline) { throw 'The separate lock-holder process did not become ready.' }
            Start-Sleep -Milliseconds 50
        }
        Assert-ExpectedFailure {
            $plan = Get-ChatBoxDataPlan
            Assert-ChatBoxDataPlan -Plan $plan
            Stop-ChatBoxLocalServices -Plan $plan
            $null = Remove-ChatBoxLocalData -Plan $plan
        } $case.Name
        Assert-Safety ([IO.File]::ReadAllText((Join-Path $case.Root 'must-survive.txt')) -ceq 'busy-owner-data') ($case.Name + ' allowed deletion of active data.')
    }
    finally {
        Set-SyntheticFile $releasePath 'release'
        if (-not $holder.WaitForExit(5000)) { $holder.Kill(); [void] $holder.WaitForExit(5000) }
        $holder.Dispose()
    }
    Remove-SyntheticState
}

# Reusing an independently configured SQLite database does not make the whole
# parent directory disposable. Default removal keeps it; explicit removal is
# confined to the selected SQLite file and its own sidecars.
$externalDatabase = Join-Path $fixtureRoot 'external-store\shared.sqlite3'
[void] [IO.Directory]::CreateDirectory((Split-Path -Parent $externalDatabase))
$databaseFixtureScript = Join-Path $fixtureRoot 'create-sqlite.py'
Set-SyntheticFile $databaseFixtureScript @'
import sqlite3
import sys
with sqlite3.connect(sys.argv[1]) as connection:
    connection.execute("create table fixture(value text)")
    connection.execute("insert into fixture values (?)", ("synthetic",))
'@
& $python.Source $databaseFixtureScript $externalDatabase
if ($LASTEXITCODE -ne 0) { throw 'Could not create the synthetic external SQLite database.' }
$databaseHash = (Get-FileHash -LiteralPath $externalDatabase -Algorithm SHA256).Hash
$externalNeighbor = Join-Path (Split-Path -Parent $externalDatabase) 'unrelated.txt'
Set-SyntheticFile $externalNeighbor 'retain-external-neighbor'
Write-ExternalConfig $externalDatabase
$plan = Get-ChatBoxDataPlan
Assert-ChatBoxDataPlan -Plan $plan
Stop-ChatBoxLocalServices -Plan $plan
$result = Remove-ChatBoxLocalData -Plan $plan
Assert-Safety ((Get-FileHash -LiteralPath $externalDatabase -Algorithm SHA256).Hash -ceq $databaseHash) 'Default local-data removal changed the external database.'
Assert-Safety (-not $result.ExternalDatabaseDeleted) 'Default local-data removal reported the external database deleted.'
Assert-Safety (@(Test-ChatBoxDataRemoved -Plan $plan).Count -eq 0) 'Default cleanup incorrectly left managed local data.'
Write-SafetyEvidence 'Default cleanup preserved the separately configured external SQLite database.'

foreach ($suffix in @('-wal', '-shm', '-journal', '.serve.lock')) {
    Set-SyntheticFile ($externalDatabase + $suffix) ('synthetic-sidecar-' + $suffix)
}
Write-ExternalConfig $externalDatabase
$plan = Get-ChatBoxDataPlan
Assert-ChatBoxDataPlan -Plan $plan
Stop-ChatBoxLocalServices -Plan $plan
$result = Remove-ChatBoxLocalData -Plan $plan -DeleteExternalDatabase
Assert-Safety $result.ExternalDatabaseDeleted 'Explicit external-database removal was not reported.'
foreach ($suffix in @('', '-wal', '-shm', '-journal', '.serve.lock')) {
    Assert-Safety (-not (Test-Path -LiteralPath ($externalDatabase + $suffix))) "Explicit database cleanup left the selected file/sidecar: $suffix"
}
Assert-Safety ([IO.File]::ReadAllText($externalNeighbor) -ceq 'retain-external-neighbor') 'External cleanup removed an unrelated file in the database directory.'
Assert-Safety (@(Test-ChatBoxDataRemoved -Plan $plan -DeleteExternalDatabase).Count -eq 0) 'Explicit database cleanup reported unremoved managed data.'
Write-SafetyEvidence 'Explicit external-database cleanup removed only its SQLite file and exact sidecars; adjacent files were retained.'

$notDatabase = Join-Path $fixtureRoot 'external-store\unrelated-config.json'
Set-SyntheticFile $notDatabase '{"this":"is not a SQLite database"}'
Set-SyntheticFile (Join-Path $dataRoot 'history.json') 'retain-on-invalid-external-database'
Write-ExternalConfig $notDatabase
$plan = Get-ChatBoxDataPlan
Stop-ChatBoxLocalServices -Plan $plan
Assert-ExpectedFailure { $null = Remove-ChatBoxLocalData -Plan $plan -DeleteExternalDatabase } 'A non-SQLite file configured as an external database'
Assert-Safety ([IO.File]::ReadAllText($notDatabase) -ceq '{"this":"is not a SQLite database"}') 'An unrelated non-SQLite file was deleted.'
Assert-Safety ([IO.File]::ReadAllText((Join-Path $dataRoot 'history.json')) -ceq 'retain-on-invalid-external-database') 'Local history was deleted before the external database was validated.'
Remove-SyntheticState

foreach ($path in ($ownedRoots + @($startupPath))) {
    Assert-Safety (-not (Test-Path -LiteralPath $path)) "The safety test left its own synthetic state: $path"
}
Write-SafetyEvidence 'Uninstall safety checks passed: root/nested junctions, foreign startup link, live owner/controller locks, external database boundaries, and invalid SQLite identity.'
