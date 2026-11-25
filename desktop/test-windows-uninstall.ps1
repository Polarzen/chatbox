[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $MsiPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# This test really installs and removes an MSI and current-user data. It must
# never be used as a cleanup command on a developer's or customer's computer.
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:OS -ne 'Windows_NT') {
    throw 'The complete-uninstall test is restricted to a fresh GitHub-hosted Windows Actions runner.'
}

$runnerTemp = [IO.Path]::GetFullPath($env:RUNNER_TEMP).TrimEnd('\')
$testRoot = Join-Path $runnerTemp ('chatbox-uninstall-regression-' + [Guid]::NewGuid().ToString('N'))
$installDir = Join-Path $testRoot 'install with spaces'
$summaryPath = Join-Path $testRoot 'uninstall-summary.log'
$resultPath = Join-Path $testRoot 'uninstall-result.json'
$upgradeCode = '{204B3F78-64EC-4071-9A66-A535773540EB}'
$localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
$userProfile = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
$startupDir = [Environment]::GetFolderPath([Environment+SpecialFolder]::Startup)
$dataDir = Join-Path $localAppData 'Chatbox'
$runtimeParent = Join-Path $localAppData 'Polarzen\Chatbox'
$runtimeDir = Join-Path $runtimeParent 'device-runtime'
$fallbackDir = Join-Path $userProfile '.chatbox'
$endpointDir = Join-Path $userProfile '.polarzen-chatbox-tmp'
$startupLink = Join-Path $startupDir 'Polarzen Chatbox Device Host.lnk'
$fixtureRoots = @($dataDir, $runtimeDir, $fallbackDir, $endpointDir)
$fixtureId = [Guid]::NewGuid().ToString('N')
$installer = $null
$database = $null
$shortcutShell = $null
$managedService = $null
$unrelatedService = $null

function Assert-That([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Write-Evidence([string] $Message) {
    Add-Content -LiteralPath $summaryPath -Value $Message -Encoding UTF8
    Write-Host $Message
}

function Get-MsiRows($Database, [string] $Table, [string[]] $Columns) {
    $view = $null
    try {
        $names = ($Columns | ForEach-Object { '`' + $_ + '`' }) -join ', '
        $view = $Database.OpenView('SELECT ' + $names + ' FROM `' + $Table + '`')
        [void] $view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            try {
                $row = [ordered]@{}
                for ($column = 0; $column -lt $Columns.Count; $column++) {
                    $row[$Columns[$column]] = $record.StringData($column + 1)
                }
                [pscustomobject] $row
            }
            finally { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) }
        }
    }
    finally {
        if ($null -ne $view) {
            [void] $view.Close()
            [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
        }
    }
}

function Get-RelatedProducts {
    return @($installer.RelatedProducts($upgradeCode))
}

function Test-RegistryValue($Row) {
    $hive = switch ([int] $Row.Root) {
        -1 { [Microsoft.Win32.RegistryHive]::LocalMachine }
        0 { [Microsoft.Win32.RegistryHive]::ClassesRoot }
        1 { [Microsoft.Win32.RegistryHive]::CurrentUser }
        2 { [Microsoft.Win32.RegistryHive]::LocalMachine }
        3 { [Microsoft.Win32.RegistryHive]::Users }
        default { throw "Unsupported MSI Registry root: $($Row.Root)" }
    }
    $view = if ($Row.Is64Bit) { [Microsoft.Win32.RegistryView]::Registry64 } else { [Microsoft.Win32.RegistryView]::Registry32 }
    $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey($hive, $view)
    $key = $null
    try {
        $key = $base.OpenSubKey($Row.Key, $false)
        if ($null -eq $key) { return $false }
        return @($key.GetValueNames()) -contains $Row.Name
    }
    finally {
        if ($null -ne $key) { $key.Dispose() }
        $base.Dispose()
    }
}

function Write-FixtureFile([string] $Path, [string] $Content) {
    [void] [IO.Directory]::CreateDirectory((Split-Path -Parent $Path))
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Get-FixtureSnapshot {
    $snapshot = [ordered]@{}
    foreach ($root in $fixtureRoots) {
        Assert-That (Test-Path -LiteralPath $root -PathType Container) "Fixture root is missing: $root"
        foreach ($file in @(Get-ChildItem -LiteralPath $root -Recurse -File -Force | Sort-Object FullName)) {
            $snapshot[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        }
    }
    Assert-That (Test-Path -LiteralPath $startupLink -PathType Leaf) 'The startup fixture shortcut is missing.'
    $snapshot[$startupLink] = (Get-FileHash -LiteralPath $startupLink -Algorithm SHA256).Hash
    return ($snapshot | ConvertTo-Json -Compress)
}

function Invoke-UninstallEntry([string] $Script, [string] $Mode, [string] $Name) {
    $stdout = Join-Path $testRoot ($Name + '.stdout.log')
    $stderr = Join-Path $testRoot ($Name + '.stderr.log')
    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $arguments = '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $Script + '" ' + $Mode
    $process = Start-Process -FilePath $powershell -ArgumentList $arguments -WorkingDirectory $testRoot `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -NoNewWindow
    if (-not $process.WaitForExit(180000)) {
        # Only terminate the exact test process created above, never an image-name match.
        $process.Kill()
        throw "The $Name uninstaller process did not finish within 180 seconds."
    }
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    foreach ($path in @($stdout, $stderr)) {
        if ((Test-Path -LiteralPath $path) -and (Get-Item -LiteralPath $path).Length -gt 0) {
            Get-Content -LiteralPath $path | ForEach-Object { Write-Host $_ }
        }
    }
    return $exitCode
}

function Get-ProductShortcuts([string[]] $Targets) {
    $programFolders = @(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::Programs),
        [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonPrograms)
    ) | Select-Object -Unique
    foreach ($directory in $programFolders) {
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $directory -Filter '*.lnk' -Recurse -File)) {
            $link = $null
            try {
                $link = $shortcutShell.CreateShortcut($file.FullName)
                if ($Targets -contains [string] $link.TargetPath) {
                    [pscustomobject]@{ Path = $file.FullName; Target = [string] $link.TargetPath }
                }
            }
            finally { if ($null -ne $link) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($link) } }
        }
    }
}

try {
    Assert-That ($testRoot.StartsWith($runnerTemp + '\', [StringComparison]::OrdinalIgnoreCase)) 'The test root must be inside RUNNER_TEMP.'
    Assert-That (-not [string]::IsNullOrWhiteSpace($localAppData) -and -not [string]::IsNullOrWhiteSpace($userProfile) -and
        -not [string]::IsNullOrWhiteSpace($startupDir)) 'Required current-user special folders are unavailable.'
    Assert-That ([IO.Path]::GetFullPath($env:LOCALAPPDATA).TrimEnd('\') -ieq $localAppData.TrimEnd('\')) 'LOCALAPPDATA must be the actual runner user folder.'
    Assert-That ([IO.Path]::GetFullPath($env:USERPROFILE).TrimEnd('\') -ieq $userProfile.TrimEnd('\')) 'USERPROFILE must be the actual runner user folder.'
    foreach ($path in ($fixtureRoots + @($startupLink))) {
        Assert-That (-not (Test-Path -LiteralPath $path)) "Refusing to overwrite pre-existing Chat Box data: $path"
    }

    $msi = (Resolve-Path -LiteralPath $MsiPath).Path
    Assert-That ([IO.Path]::GetExtension($msi) -ieq '.msi') 'Expected a Windows MSI file.'
    $installer = New-Object -ComObject WindowsInstaller.Installer
    Assert-That (@(Get-RelatedProducts).Count -eq 0) 'A Chat Box MSI is already installed; this test requires an isolated runner.'
    $database = $installer.OpenDatabase($msi, 0)
    $properties = @{}
    foreach ($row in @(Get-MsiRows $database 'Property' @('Property', 'Value'))) { $properties[$row.Property] = $row.Value }
    Assert-That ($properties['UpgradeCode'] -ieq $upgradeCode) 'The candidate MSI has an unexpected UpgradeCode.'
    Assert-That ($properties['ALLUSERS'] -eq '1') 'The candidate must be the existing per-machine MSI product.'
    $productCode = $properties['ProductCode']
    $component64 = @{}
    foreach ($row in @(Get-MsiRows $database 'Component' @('Component', 'Attributes'))) {
        $component64[$row.Component] = (([int] $row.Attributes -band 256) -ne 0)
    }
    $registryRows = @(
        foreach ($row in @(Get-MsiRows $database 'Registry' @('Registry', 'Root', 'Key', 'Name', 'Value', 'Component_'))) {
            if ($row.Name -in @('+', '-', '*') -and [string]::IsNullOrEmpty($row.Value)) { continue }
            $key = [regex]::Replace($row.Key, '\[([A-Za-z_][A-Za-z_0-9]*)\]', {
                param($match)
                $property = $match.Groups[1].Value
                if (-not $properties.ContainsKey($property)) { throw "Cannot resolve MSI registry key property: $property" }
                return $properties[$property]
            })
            [pscustomobject]@{
                Id = $row.Registry; Root = [int] $row.Root; Key = $key; Name = [string] $row.Name
                Is64Bit = $component64[$row.Component_]
            }
        }
    )
    Assert-That ($registryRows.Count -gt 0) 'No MSI registry rows were found.'
    foreach ($row in $registryRows) {
        Assert-That (-not (Test-RegistryValue $row)) "The runner already contains candidate registry data: $($row.Key) / $($row.Name)"
    }
    [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
    $database = $null

    [void] [IO.Directory]::CreateDirectory($testRoot)
    Write-Evidence "Candidate MSI SHA-256: $((Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash)"
    Write-Evidence "ProductCode: $productCode; version: $($properties['ProductVersion']); isolated install: $installDir"
    & (Join-Path $PSScriptRoot 'test-uninstall-safety.ps1') -EvidenceDirectory $testRoot
    foreach ($path in ($fixtureRoots + @($startupLink))) {
        Assert-That (-not (Test-Path -LiteralPath $path)) "Safety tests left synthetic state before the real MSI test: $path"
    }
    $installLog = Join-Path $testRoot 'install.log'
    $arguments = "/i `"$msi`" /qn /norestart INSTALLDIR=`"$installDir`" /L*v `"$installLog`""
    $installation = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $arguments -Wait -PassThru -NoNewWindow
    Assert-That ($installation.ExitCode -in @(0, 3010)) "MSI installation failed with exit code $($installation.ExitCode)."
    $registered = @(Get-RelatedProducts)
    Assert-That ($registered.Count -eq 1 -and $registered[0] -ieq $productCode) 'The expected MSI was not the sole installed Chat Box product.'

    $installedExe = Join-Path $installDir 'Chatbox.exe'
    $uninstallCmd = Join-Path $installDir 'uninstall.cmd'
    $uninstallScript = Join-Path $installDir 'Uninstall-ChatBox.ps1'
    $uninstallData = Join-Path $installDir 'Uninstall-ChatBoxData.ps1'
    foreach ($path in @($installedExe, $uninstallCmd, $uninstallScript, $uninstallData)) {
        Assert-That (Test-Path -LiteralPath $path -PathType Leaf) "MSI did not install the expected file: $path"
    }
    foreach ($name in @('uninstall.cmd', 'Uninstall-ChatBox.ps1', 'Uninstall-ChatBoxData.ps1')) {
        $source = Join-Path $PSScriptRoot ('uninstall\' + $name)
        Assert-That ((Get-FileHash -LiteralPath (Join-Path $installDir $name)).Hash -eq (Get-FileHash -LiteralPath $source).Hash) "Packaged $name differs from the checked-in implementation."
    }
    $trackedFiles = @(Get-ChildItem -LiteralPath $installDir -Recurse -File -Force | ForEach-Object FullName)
    Assert-That ($trackedFiles.Count -gt 20) 'The installed file snapshot unexpectedly lacks the bundled application/runtime.'
    foreach ($row in $registryRows) {
        Assert-That (Test-RegistryValue $row) "The MSI did not install registry value $($row.Id): $($row.Key) / $($row.Name)"
    }
    $protocolRows = @($registryRows | Where-Object { $_.Key -like 'Software\Classes\polarzen-chatbox*' })
    Assert-That ($protocolRows.Count -eq 3) 'Expected all three notification protocol registry values.'
    $shortcutShell = New-Object -ComObject WScript.Shell
    $shortcuts = @(Get-ProductShortcuts @($installedExe, $uninstallCmd))
    Assert-That (@($shortcuts | Where-Object Target -IEQ $uninstallCmd).Count -eq 1) 'The Start menu full-uninstall shortcut was not installed exactly once.'
    Assert-That (@($shortcuts | Where-Object Target -IEQ $installedExe).Count -ge 1) 'The main Start menu shortcut was not installed.'

    # All following roots were proven absent above; only synthetic data is used.
    Write-FixtureFile (Join-Path $dataDir 'history.json') '{"profiles":[],"fixture":"chatbox-uninstall"}'
    Write-FixtureFile (Join-Path $dataDir 'history.json.pre-migration.bak') 'synthetic history backup'
    Write-FixtureFile (Join-Path $dataDir 'credentials\fixture.dpapi') 'synthetic encrypted-credential fixture'
    Write-FixtureFile (Join-Path $dataDir 'composer-media\draft.bin') 'synthetic draft'
    Write-FixtureFile (Join-Path $dataDir 'reply-images\image.bin') 'synthetic image cache'
    Write-FixtureFile (Join-Path $dataDir 'reply-audio\audio.wav') 'synthetic voice cache'
    Write-FixtureFile (Join-Path $dataDir 'shell.json') '{"fixture":true}'
    Write-FixtureFile (Join-Path $fallbackDir 'history.json') 'synthetic fallback history'
    Write-FixtureFile (Join-Path $endpointDir (('b' * 64) + '.endpoint')) 'synthetic activation endpoint'
    $generation = [Guid]::NewGuid().ToString()
    $runtimeScript = Join-Path $runtimeDir ('program\' + ('a' * 64) + '\device_runtime.py')
    $serviceFixture = @'
"""CI-only service fixture using the production stop-file and byte-lock protocol."""
import argparse
import json
import msvcrt
import os
from pathlib import Path
import time

parser = argparse.ArgumentParser()
parser.add_argument("--root", type=Path, required=True)
parser.add_argument("action", choices=("serve",))
parser.add_argument("generation")
args = parser.parse_args()
root = args.root
evidence = Path(os.environ["CHATBOX_UNINSTALL_TEST_EVIDENCE"])
streams = []
try:
    for path in (root / "device-host.serve.lock", root / "device.sqlite3.serve.lock"):
        stream = open(path, "a+b")
        if path.stat().st_size == 0:
            stream.write(b"\0")
            stream.flush()
        stream.seek(0)
        msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        streams.append(stream)
    (evidence / "managed-service-ready.txt").write_text("locked", encoding="ascii")
    deadline = time.monotonic() + 150
    while not (root / ("stop-" + args.generation)).exists():
        if time.monotonic() > deadline:
            raise RuntimeError("The uninstaller never requested a graceful stop")
        time.sleep(.05)
    observed = {
        "stopRequested": True,
        "dataStillPresentAtStop": (root / "device.sqlite3").is_file(),
    }
finally:
    for stream in reversed(streams):
        stream.seek(0)
        msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        stream.close()
(evidence / "managed-service-stopped.json").write_text(json.dumps(observed), encoding="ascii")
'@
    Write-FixtureFile $runtimeScript $serviceFixture
    Write-FixtureFile (Join-Path $runtimeDir 'device.sqlite3') 'synthetic device database'
    Write-FixtureFile (Join-Path $runtimeDir 'device.sqlite3-wal') 'synthetic database WAL'
    Write-FixtureFile (Join-Path $runtimeDir 'device.sqlite3-shm') 'synthetic database SHM'
    Write-FixtureFile (Join-Path $runtimeDir 'credentials.dpapi') 'synthetic device credential'
    Write-FixtureFile (Join-Path $runtimeDir 'config.json') (@{
        generation = $generation; dbPath = (Join-Path $runtimeDir 'device.sqlite3'); autostart = $true
        mode = 'trusted'; gatewayPort = 18766; phoneUrl = 'https://example.invalid'; ngrokPath = ''
    } | ConvertTo-Json)
    Write-FixtureFile (Join-Path $runtimeDir 'running.json') (@{ generation = $generation; error = 'service_offline' } | ConvertTo-Json)
    $pythonCommand = Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $startup = $shortcutShell.CreateShortcut($startupLink)
    try {
        $startup.TargetPath = $pythonCommand.Source
        $startup.Arguments = '-E -s "' + $runtimeScript + '" --root "' + $runtimeDir + '" resume'
        $startup.WorkingDirectory = $runtimeDir
        $startup.Save()
    }
    finally { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($startup) }

    # These sentinels deliberately share parent directories with managed data.
    # The install-directory sentinel specifically catches recursive uninstall.
    $sentinels = @(
        (Join-Path $installDir 'unrelated-user-file.txt'),
        (Join-Path $localAppData ('Chatbox-neighbor-' + $fixtureId + '\keep.txt')),
        (Join-Path $runtimeParent ('neighbor-' + $fixtureId + '\keep.txt')),
        (Join-Path $userProfile ('.chatbox-neighbor-' + $fixtureId + '\keep.txt')),
        (Join-Path $userProfile ('.polarzen-chatbox-tmp-neighbor-' + $fixtureId + '\keep.txt')),
        (Join-Path $startupDir ('unrelated-startup-' + $fixtureId + '.txt'))
    )
    foreach ($path in $sentinels) { Write-FixtureFile $path ('retain-' + $fixtureId) }
    $before = Get-FixtureSnapshot
    [IO.File]::WriteAllText((Join-Path $testRoot 'fixture-before.json'), $before)
    Write-Evidence "Installed $($trackedFiles.Count) tracked files and $($registryRows.Count) registry values; created isolated synthetic user data and neighboring sentinels."

    $previewExit = Invoke-UninstallEntry $uninstallScript '-WhatIf' 'preview'
    Assert-That ($previewExit -eq 0) "Uninstall -WhatIf failed with exit code $previewExit."
    Assert-That ((Get-FixtureSnapshot) -ceq $before) 'Uninstall -WhatIf modified user data or the startup shortcut.'
    Assert-That ((Get-RelatedProducts) -contains $productCode) 'Uninstall -WhatIf removed the MSI product.'
    foreach ($path in $trackedFiles) { Assert-That (Test-Path -LiteralPath $path -PathType Leaf) "Uninstall -WhatIf removed $path" }
    foreach ($row in $registryRows) { Assert-That (Test-RegistryValue $row) "Uninstall -WhatIf removed registry value $($row.Id)." }
    Write-Evidence 'The installed Windows PowerShell 5.1 -WhatIf entry returned synchronously without changing the MSI, registry, user files, or startup shortcut.'

    # Exercise the real cross-process Windows locking and graceful stop protocol.
    # No HTTP server, model, real account, or ngrok process is involved.
    $oldFixtureEvidence = $env:CHATBOX_UNINSTALL_TEST_EVIDENCE
    try {
        $env:CHATBOX_UNINSTALL_TEST_EVIDENCE = $testRoot
        $serviceArguments = '-E -s "' + $runtimeScript + '" --root "' + $runtimeDir + '" serve ' + $generation
        $managedService = Start-Process -FilePath $pythonCommand.Source -ArgumentList $serviceArguments -PassThru -NoNewWindow -RedirectStandardOutput (Join-Path $testRoot 'managed-service.stdout.log') -RedirectStandardError (Join-Path $testRoot 'managed-service.stderr.log')
    }
    finally { $env:CHATBOX_UNINSTALL_TEST_EVIDENCE = $oldFixtureEvidence }
    $readyMarker = Join-Path $testRoot 'managed-service-ready.txt'
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(15)
    while (-not (Test-Path -LiteralPath $readyMarker)) {
        if ($managedService.HasExited) { throw 'The managed Python fixture exited before taking its Windows locks.' }
        if ([DateTime]::UtcNow -gt $readyDeadline) { throw 'The managed Python fixture did not become ready.' }
        Start-Sleep -Milliseconds 100
    }
    $unrelatedScript = Join-Path $testRoot 'unrelated-python.py'
    Write-FixtureFile $unrelatedScript 'import time; time.sleep(300)'
    $unrelatedService = Start-Process -FilePath $pythonCommand.Source -ArgumentList ('-E -s "' + $unrelatedScript + '"') -PassThru -NoNewWindow
    Assert-That (-not $unrelatedService.HasExited) 'The unrelated Python sentinel did not start.'

    $uninstallExit = Invoke-UninstallEntry $uninstallScript '-Yes' 'complete-uninstall'
    Assert-That ($uninstallExit -in @(0, 3010)) "Complete uninstall failed with exit code $uninstallExit."
    Assert-That ($managedService.WaitForExit(5000)) 'The managed service remained alive after full uninstall.'
    Assert-That ($managedService.ExitCode -eq 0) 'The managed service did not exit through its graceful stop protocol.'
    $stoppedMarker = Join-Path $testRoot 'managed-service-stopped.json'
    Assert-That (Test-Path -LiteralPath $stoppedMarker -PathType Leaf) 'The managed service was terminated without observing its stop file.'
    $stopped = Get-Content -LiteralPath $stoppedMarker -Raw | ConvertFrom-Json
    Assert-That ($stopped.stopRequested -and $stopped.dataStillPresentAtStop) 'Service data was deleted before the managed service handled its stop request.'
    Assert-That (-not $unrelatedService.HasExited) 'The uninstaller terminated an unrelated Python process.'
    Assert-That (@(Get-RelatedProducts).Count -eq 0) 'The MSI product is still registered after complete uninstall.'
    foreach ($row in $registryRows) {
        Assert-That (-not (Test-RegistryValue $row)) "MSI registry value remains after uninstall: $($row.Key) / $($row.Name)"
    }
    foreach ($path in $trackedFiles) { Assert-That (-not (Test-Path -LiteralPath $path)) "A tracked MSI file remains: $path" }
    foreach ($path in ($fixtureRoots + @($startupLink))) {
        Assert-That (-not (Test-Path -LiteralPath $path)) "A managed data directory or startup shortcut remains: $path"
    }
    foreach ($shortcut in $shortcuts) {
        Assert-That (-not (Test-Path -LiteralPath $shortcut.Path)) "An MSI Start menu shortcut remains: $($shortcut.Path)"
    }
    foreach ($path in $sentinels) {
        Assert-That ((Test-Path -LiteralPath $path -PathType Leaf) -and
            [IO.File]::ReadAllText($path) -ceq ('retain-' + $fixtureId)) "Complete uninstall removed or modified an unrelated sentinel: $path"
    }
    Write-Evidence 'Complete uninstall removed the registered MSI, every captured MSI registry value/file/shortcut, all four user-data roots, and login autostart.'
    Write-Evidence 'Unrelated files inside the install directory and beside each data/Startup location were preserved.'
    Write-Evidence 'The owned Python service observed stop-generation while its data still existed and released its Windows locks; an unrelated Python process remained running.'
    @{
        result = 'passed'; productCode = $productCode; version = $properties['ProductVersion']
        msiSha256 = (Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash
        trackedFilesRemoved = $trackedFiles.Count; registryValuesRemoved = $registryRows.Count
        dataRootsRemoved = $fixtureRoots.Count; startMenuShortcutsRemoved = $shortcuts.Count
        neighboringSentinelsPreserved = $sentinels.Count; whatIfUnchanged = $true
        gracefulServiceStop = $true; unrelatedPythonPreserved = $true
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resultPath -Encoding UTF8
}
catch {
    if (Test-Path -LiteralPath $testRoot -PathType Container) {
        Write-Evidence ('FAILED: ' + $_.Exception.Message)
        @{ result = 'failed'; error = $_.Exception.Message } | ConvertTo-Json | Set-Content -LiteralPath $resultPath -Encoding UTF8
    }
    throw
}
finally {
    # Preserve failed-run diagnostics. The ephemeral runner owns every fixture.
    foreach ($process in @($managedService, $unrelatedService)) {
        if ($null -ne $process) {
            try { if (-not $process.HasExited) { $process.Kill(); [void] $process.WaitForExit(5000) } }
            finally { $process.Dispose() }
        }
    }
    if ($null -ne $shortcutShell) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($shortcutShell) }
    if ($null -ne $database) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) }
    if ($null -ne $installer) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) }
}
