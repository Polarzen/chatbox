# Windows PowerShell 5.1. Dot-sourcing this file only defines functions.
# All destructive paths are derived from this user's Windows known folders.

function ConvertTo-ChatBoxFullPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ($Path -notmatch '^(?:[A-Za-z]:[\\/]|[\\/]{2}[^\\/]+[\\/][^\\/]+)') {
        throw "Expected an absolute path: $Path"
    }
    $full = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetPathRoot($full)
    if ([string]::Equals($full, $root, [StringComparison]::OrdinalIgnoreCase)) { return $root }
    return $full.TrimEnd([char[]]@('\', '/'))
}

function Test-ChatBoxSamePath {
    param([string]$First, [string]$Second)
    if ([string]::IsNullOrWhiteSpace($First) -or [string]::IsNullOrWhiteSpace($Second)) {
        return [string]::IsNullOrWhiteSpace($First) -and [string]::IsNullOrWhiteSpace($Second)
    }
    return [string]::Equals((ConvertTo-ChatBoxFullPath $First), (ConvertTo-ChatBoxFullPath $Second),
        [StringComparison]::OrdinalIgnoreCase)
}

function Test-ChatBoxPathWithin {
    param([string]$Path, [string]$Root)
    $full = ConvertTo-ChatBoxFullPath $Path
    $parent = ConvertTo-ChatBoxFullPath $Root
    return (Test-ChatBoxSamePath $full $parent) -or
        $full.StartsWith($parent.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Get-ChatBoxPathAttributes {
    param([Parameter(Mandatory = $true)][string]$Path)
    try { return [IO.File]::GetAttributes($Path) }
    catch [IO.FileNotFoundException] { return $null }
    catch [IO.DirectoryNotFoundException] { return $null }
}

function Assert-ChatBoxNoReparsePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    $full = ConvertTo-ChatBoxFullPath $Path
    $chain = New-Object 'System.Collections.Generic.List[string]'
    $cursor = $full
    while (-not [string]::IsNullOrEmpty($cursor)) {
        $chain.Add($cursor)
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ([string]::Equals($parent, $cursor, [StringComparison]::OrdinalIgnoreCase)) { break }
        $cursor = $parent
    }
    # Inspect ancestors first, before an operation can traverse a linked directory.
    for ($index = $chain.Count - 1; $index -ge 0; $index--) {
        $attributes = Get-ChatBoxPathAttributes $chain[$index]
        if ($null -ne $attributes -and ($attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing a reparse point (junction or symbolic link): $($chain[$index])"
        }
    }
}

function Assert-ChatBoxSafeTree {
    param([Parameter(Mandatory = $true)][string]$Root)
    Assert-ChatBoxNoReparsePath $Root
    $attributes = Get-ChatBoxPathAttributes $Root
    if ($null -eq $attributes) { return }
    if (-not ($attributes -band [IO.FileAttributes]::Directory)) {
        throw "The Chat Box data directory is occupied by a file: $Root"
    }
    $pending = New-Object 'System.Collections.Generic.Stack[string]'
    $pending.Push($Root)
    while ($pending.Count -gt 0) {
        $directory = $pending.Pop()
        Assert-ChatBoxNoReparsePath $directory
        foreach ($item in @(Get-ChildItem -LiteralPath $directory -Force -ErrorAction Stop)) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing a reparse point inside Chat Box data: $($item.FullName)"
            }
            if ($item.Attributes -band [IO.FileAttributes]::Directory) { $pending.Push($item.FullName) }
        }
    }
}

function Get-ChatBoxKnownLocations {
    $local = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    $userProfilePath = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $startup = [Environment]::GetFolderPath([Environment+SpecialFolder]::Startup)
    foreach ($path in @($local, $userProfilePath, $startup)) {
        if ([string]::IsNullOrWhiteSpace($path)) { throw 'A current-user Windows known folder is unavailable.' }
    }
    $runtime = ConvertTo-ChatBoxFullPath (Join-Path $local 'Polarzen\Chatbox\device-runtime')
    return [pscustomobject]@{
        Roots = [string[]]@(
            (ConvertTo-ChatBoxFullPath (Join-Path $local 'Chatbox')),
            $runtime,
            (ConvertTo-ChatBoxFullPath (Join-Path $userProfilePath '.chatbox')),
            (ConvertTo-ChatBoxFullPath (Join-Path $userProfilePath '.polarzen-chatbox-tmp'))
        )
        RuntimeRoot = $runtime
        StartupShortcut = ConvertTo-ChatBoxFullPath (Join-Path $startup 'Polarzen Chatbox Device Host.lnk')
        CurrentUserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    }
}

function Read-ChatBoxRuntimeConfig {
    param([Parameter(Mandatory = $true)][string]$RuntimeRoot)
    $file = Join-Path $RuntimeRoot 'config.json'
    Assert-ChatBoxNoReparsePath $file
    $attributes = Get-ChatBoxPathAttributes $file
    if ($null -eq $attributes) { return $null }
    if ($attributes -band [IO.FileAttributes]::Directory) { throw 'The device runtime configuration is not a file.' }
    $info = Get-Item -LiteralPath $file -Force -ErrorAction Stop
    if ($info.Length -gt 65536) { throw 'The device runtime configuration is unexpectedly large.' }
    $config = [IO.File]::ReadAllText($file) | ConvertFrom-Json -ErrorAction Stop
    if ($null -eq $config -or $config -is [Array] -or $config -isnot [pscustomobject]) {
        throw 'The device runtime configuration is not a JSON object.'
    }
    return $config
}

function Get-ChatBoxDatabaseLocations {
    param($Locations, $Config)
    $database = $null
    $external = $null
    if ($null -ne $Config -and $null -ne $Config.PSObject.Properties['dbPath']) {
        if ($Config.dbPath -isnot [string] -or [string]::IsNullOrWhiteSpace($Config.dbPath)) {
            throw 'The device runtime database path is invalid.'
        }
        $database = ConvertTo-ChatBoxFullPath $Config.dbPath
        $managed = $false
        foreach ($root in $Locations.Roots) {
            if (Test-ChatBoxPathWithin $database $root) { $managed = $true; break }
        }
        if (-not $managed) { $external = $database }
    }
    return [pscustomobject]@{ DatabasePath = $database; ExternalDatabase = $external }
}

function Get-ChatBoxDataPlan {
    $locations = Get-ChatBoxKnownLocations
    $config = Read-ChatBoxRuntimeConfig $locations.RuntimeRoot
    $database = Get-ChatBoxDatabaseLocations $locations $config
    return [pscustomobject]@{
        Roots = $locations.Roots
        RuntimeRoot = $locations.RuntimeRoot
        StartupShortcut = $locations.StartupShortcut
        CurrentUserSid = $locations.CurrentUserSid
        DatabasePath = $database.DatabasePath
        ExternalDatabase = $database.ExternalDatabase
    }
}

function Assert-ChatBoxPlanLocations {
    param([Parameter(Mandatory = $true)]$Plan)
    $expected = Get-ChatBoxKnownLocations
    if ($null -eq $Plan.PSObject.Properties['Roots'] -or @($Plan.Roots).Count -ne $expected.Roots.Count) {
        throw 'The Chat Box data plan does not contain the fixed current-user roots.'
    }
    for ($index = 0; $index -lt $expected.Roots.Count; $index++) {
        if (-not (Test-ChatBoxSamePath $Plan.Roots[$index] $expected.Roots[$index])) {
            throw 'The Chat Box data plan contains an unexpected root.'
        }
    }
    foreach ($property in @('RuntimeRoot', 'StartupShortcut')) {
        if ($null -eq $Plan.PSObject.Properties[$property] -or
            -not (Test-ChatBoxSamePath $Plan.$property $expected.$property)) {
            throw "The Chat Box data plan contains an unexpected $property."
        }
    }
    if ($null -eq $Plan.PSObject.Properties['CurrentUserSid'] -or $Plan.CurrentUserSid -ne $expected.CurrentUserSid) {
        throw 'The Chat Box data plan belongs to a different Windows user.'
    }
}

function Split-ChatBoxWindowsArguments {
    param([Parameter(Mandatory = $true)][string]$Arguments)
    if (-not ('ChatBoxUninstall.CommandLine' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace ChatBoxUninstall {
    public static class CommandLine {
        [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CommandLineToArgvW(string command, out int count);
        [DllImport("kernel32.dll")]
        private static extern IntPtr LocalFree(IntPtr memory);
        public static string[] Split(string arguments) {
            int count;
            IntPtr values = CommandLineToArgvW("chatbox-uninstall " + arguments, out count);
            if (values == IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
            try {
                var result = new string[Math.Max(0, count - 1)];
                for (int i = 1; i < count; i++)
                    result[i - 1] = Marshal.PtrToStringUni(Marshal.ReadIntPtr(values, i * IntPtr.Size));
                return result;
            } finally { LocalFree(values); }
        }
    }
}
'@ -ErrorAction Stop
    }
    return [ChatBoxUninstall.CommandLine]::Split($Arguments)
}

function Assert-ChatBoxStartupOwnership {
    param([Parameter(Mandatory = $true)]$Plan)
    Assert-ChatBoxNoReparsePath $Plan.StartupShortcut
    $attributes = Get-ChatBoxPathAttributes $Plan.StartupShortcut
    if ($null -eq $attributes) { return }
    if ($attributes -band [IO.FileAttributes]::Directory) { throw 'The Chat Box Startup shortcut is a directory.' }
    $shell = $null
    $shortcut = $null
    try {
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($Plan.StartupShortcut)
        $arguments = @(Split-ChatBoxWindowsArguments $shortcut.Arguments)
        $targetName = [IO.Path]::GetFileName($shortcut.TargetPath)
        $owned = ($targetName -match '^pythonw?\.exe$') -and
            (Test-ChatBoxSamePath $shortcut.WorkingDirectory $Plan.RuntimeRoot) -and
            $arguments.Count -eq 6 -and $arguments[0] -ceq '-E' -and $arguments[1] -ceq '-s' -and
            $arguments[3] -ceq '--root' -and $arguments[5] -ceq 'resume'
        if ($owned) {
            $owned = (Test-ChatBoxSamePath $arguments[4] $Plan.RuntimeRoot) -and
                (Test-ChatBoxPathWithin $arguments[2] $Plan.RuntimeRoot) -and
                [string]::Equals([IO.Path]::GetFileName($arguments[2]), 'device_runtime.py', [StringComparison]::OrdinalIgnoreCase)
        }
        if (-not $owned) { throw 'The existing Startup shortcut is not owned by this Chat Box runtime; it was preserved.' }
    }
    finally {
        if ($null -ne $shortcut) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shortcut) }
        if ($null -ne $shell) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell) }
    }
}

function Assert-ChatBoxDataPlan {
    param([Parameter(Mandatory = $true)]$Plan)
    Assert-ChatBoxPlanLocations $Plan
    foreach ($root in $Plan.Roots) { Assert-ChatBoxSafeTree $root }
    Assert-ChatBoxStartupOwnership $Plan
    $config = Read-ChatBoxRuntimeConfig $Plan.RuntimeRoot
    $current = Get-ChatBoxDatabaseLocations $Plan $config
    foreach ($property in @('DatabasePath', 'ExternalDatabase')) {
        if ($null -eq $Plan.PSObject.Properties[$property] -or
            -not (Test-ChatBoxSamePath $Plan.$property $current.$property)) {
            throw 'The shared database configuration changed; obtain a new uninstall plan before continuing.'
        }
    }
}

function Open-ChatBoxFileLock {
    param([Parameter(Mandatory = $true)][string]$Path, [switch]$Create)
    Assert-ChatBoxNoReparsePath $Path
    if (-not $Create -and $null -eq (Get-ChatBoxPathAttributes $Path)) { return $null }
    $mode = if ($Create) { [IO.FileMode]::OpenOrCreate } else { [IO.FileMode]::Open }
    $stream = [IO.File]::Open($Path, $mode, [IO.FileAccess]::ReadWrite, [IO.FileShare]::ReadWrite)
    try { $stream.Lock(0, 1) }
    catch { $stream.Dispose(); throw }
    return $stream
}

function Assert-ChatBoxIdleLock {
    param([Parameter(Mandatory = $true)][string]$Path, [string]$Description = 'Chat Box data')
    $lock = $null
    try { $lock = Open-ChatBoxFileLock $Path }
    catch { throw "$Description is still in use or its lock cannot be checked. Close the application or service and retry. $Path" }
    finally { if ($null -ne $lock) { $lock.Dispose() } }
}

function Assert-ChatBoxHistoriesIdle {
    param([Parameter(Mandatory = $true)]$Plan)
    foreach ($index in @(0, 2)) {
        Assert-ChatBoxIdleLock (Join-Path $Plan.Roots[$index] 'history.lock') 'Chat Box history'
    }
}

function Stop-ChatBoxLocalServices {
    param([Parameter(Mandatory = $true)]$Plan)
    Assert-ChatBoxDataPlan $Plan
    Assert-ChatBoxHistoriesIdle $Plan
    $controller = $null
    try {
        if ($null -ne (Get-ChatBoxPathAttributes $Plan.RuntimeRoot)) {
            try { $controller = Open-ChatBoxFileLock (Join-Path $Plan.RuntimeRoot 'controller.serve.lock') -Create }
            catch { throw 'The Chat Box shared-service controller is busy. Wait for the current operation and retry.' }
        }
        # This exact shortcut was checked above; do not touch unrelated Python/ngrok startup entries.
        Assert-ChatBoxStartupOwnership $Plan
        if ($null -ne (Get-ChatBoxPathAttributes $Plan.StartupShortcut)) { [IO.File]::Delete($Plan.StartupShortcut) }
        $config = Read-ChatBoxRuntimeConfig $Plan.RuntimeRoot
        if ($null -ne $config) {
            $config | Add-Member -NotePropertyName autostart -NotePropertyValue $false -Force
            $configPath = Join-Path $Plan.RuntimeRoot 'config.json'
            $temporary = Join-Path $Plan.RuntimeRoot ('uninstall-config-' + [Guid]::NewGuid().ToString('N') + '.tmp')
            try {
                Assert-ChatBoxNoReparsePath $temporary
                [IO.File]::WriteAllText($temporary, ($config | ConvertTo-Json -Depth 16), [Text.UTF8Encoding]::new($false))
                Assert-ChatBoxNoReparsePath $configPath
                # Windows PowerShell 5.1 converts $null for a .NET string to "".
                # File.Replace needs a real CLR null when no backup path is requested.
                [IO.File]::Replace($temporary, $configPath, [NullString]::Value)
            }
            finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
            if ($null -ne $config.PSObject.Properties['generation']) {
                $generation = [Guid]::Empty
                if ($config.generation -isnot [string] -or
                    -not [Guid]::TryParseExact($config.generation, 'D', [ref]$generation)) {
                    throw 'The device runtime generation is invalid; no process was force-stopped.'
                }
                $stopFile = Join-Path $Plan.RuntimeRoot ('stop-' + $generation.ToString('D'))
                Assert-ChatBoxNoReparsePath $stopFile
                $marker = [IO.File]::Open($stopFile, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
                $marker.Dispose()
            }
        }
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        $hostLock = $null
        do {
            try {
                $hostLock = Open-ChatBoxFileLock (Join-Path $Plan.RuntimeRoot 'device-host.serve.lock')
                break
            }
            catch [IO.IOException] {
                if ([DateTime]::UtcNow -ge $deadline) {
                    throw 'The Chat Box shared service did not stop within 60 seconds. Its data was preserved.'
                }
                Start-Sleep -Milliseconds 250
            }
        } while ($true)
        if ($null -ne $hostLock) { $hostLock.Dispose() }
    }
    finally { if ($null -ne $controller) { $controller.Dispose() } }
}

function Remove-ChatBoxSafeTree {
    param([Parameter(Mandatory = $true)][string]$Root)
    Assert-ChatBoxNoReparsePath $Root
    $attributes = Get-ChatBoxPathAttributes $Root
    if ($null -eq $attributes) { return }
    if (-not ($attributes -band [IO.FileAttributes]::Directory)) { throw "Expected a Chat Box directory: $Root" }
    foreach ($item in @(Get-ChildItem -LiteralPath $Root -Force -ErrorAction Stop)) {
        Assert-ChatBoxNoReparsePath $item.FullName
        if ($item.Attributes -band [IO.FileAttributes]::Directory) {
            Remove-ChatBoxSafeTree $item.FullName
        }
        else {
            if ($item.Attributes -band [IO.FileAttributes]::ReadOnly) {
                [IO.File]::SetAttributes($item.FullName, ($item.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly)))
            }
            [IO.File]::Delete($item.FullName)
        }
    }
    Assert-ChatBoxNoReparsePath $Root
    [IO.Directory]::Delete($Root, $false)
}

function Get-ChatBoxDatabaseFiles {
    param([Parameter(Mandatory = $true)][string]$Database)
    return @($Database, ($Database + '-wal'), ($Database + '-shm'), ($Database + '-journal'), ($Database + '.serve.lock'))
}

function Assert-ChatBoxSqliteHeader {
    param([Parameter(Mandatory = $true)][string]$Database)
    Assert-ChatBoxNoReparsePath $Database
    if ($null -eq (Get-ChatBoxPathAttributes $Database)) { return }
    $stream = [IO.File]::Open($Database, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $header = New-Object byte[] 16
        if ($stream.Read($header, 0, 16) -ne 16 -or
            [Text.Encoding]::ASCII.GetString($header) -cne ("SQLite format 3" + [char]0)) {
            throw 'The external shared database does not have a SQLite header; it was preserved.'
        }
    }
    finally { $stream.Dispose() }
}

function Remove-ChatBoxExternalDatabase {
    param([Parameter(Mandatory = $true)][string]$Database)
    $files = @(Get-ChatBoxDatabaseFiles $Database)
    $any = $false
    foreach ($file in $files) {
        Assert-ChatBoxNoReparsePath $file
        $attributes = Get-ChatBoxPathAttributes $file
        if ($null -ne $attributes) {
            $any = $true
            if ($attributes -band [IO.FileAttributes]::Directory) { throw "An external database path is a directory: $file" }
        }
    }
    if (-not $any) { return }
    Assert-ChatBoxSqliteHeader $Database
    $serviceLock = $null
    $lockPath = $Database + '.serve.lock'
    try {
        try { $serviceLock = Open-ChatBoxFileLock $lockPath -Create }
        catch { throw 'The external shared database is still served or its lock is inaccessible; it was preserved.' }
        Assert-ChatBoxSqliteHeader $Database
        foreach ($file in $files) {
            if ($file -eq $lockPath) { continue }
            Assert-ChatBoxNoReparsePath $file
            [IO.File]::Delete($file)
        }
    }
    finally { if ($null -ne $serviceLock) { $serviceLock.Dispose() } }
    Assert-ChatBoxNoReparsePath $lockPath
    [IO.File]::Delete($lockPath)
}

function Remove-ChatBoxLocalData {
    param([Parameter(Mandatory = $true)]$Plan, [switch]$DeleteExternalDatabase)
    Assert-ChatBoxDataPlan $Plan
    Assert-ChatBoxHistoriesIdle $Plan
    Assert-ChatBoxIdleLock (Join-Path $Plan.RuntimeRoot 'controller.serve.lock') 'The Chat Box controller'
    Assert-ChatBoxIdleLock (Join-Path $Plan.RuntimeRoot 'device-host.serve.lock') 'The Chat Box shared service'
    Assert-ChatBoxIdleLock (Join-Path $Plan.RuntimeRoot 'device.sqlite3.serve.lock') 'The managed shared database'
    if (-not [string]::IsNullOrWhiteSpace($Plan.DatabasePath) -and
        [string]::IsNullOrWhiteSpace($Plan.ExternalDatabase)) {
        Assert-ChatBoxIdleLock ($Plan.DatabasePath + '.serve.lock') 'The managed shared database'
    }
    if ($null -ne (Get-ChatBoxPathAttributes $Plan.StartupShortcut)) {
        throw 'The Chat Box Startup shortcut still exists. Stop local services before removing data.'
    }
    $externalDeleted = $false
    $retained = New-Object 'System.Collections.Generic.List[string]'
    if (-not [string]::IsNullOrWhiteSpace($Plan.ExternalDatabase)) {
        if ($DeleteExternalDatabase) {
            Remove-ChatBoxExternalDatabase $Plan.ExternalDatabase
            $externalDeleted = $true
        }
        else {
            foreach ($file in @(Get-ChatBoxDatabaseFiles $Plan.ExternalDatabase)) {
                if ($null -ne (Get-ChatBoxPathAttributes $file)) { $retained.Add($file) }
            }
        }
    }
    $removed = New-Object 'System.Collections.Generic.List[string]'
    foreach ($root in $Plan.Roots) {
        Assert-ChatBoxSafeTree $root
        Remove-ChatBoxSafeTree $root
        $removed.Add($root)
    }
    # Prune only the empty dedicated parent. Never recursively remove Polarzen.
    $dedicatedParent = [IO.Path]::GetDirectoryName($Plan.RuntimeRoot)
    Assert-ChatBoxNoReparsePath $dedicatedParent
    if ([IO.Directory]::Exists($dedicatedParent) -and
        @(Get-ChildItem -LiteralPath $dedicatedParent -Force -ErrorAction Stop).Count -eq 0) {
        [IO.Directory]::Delete($dedicatedParent, $false)
    }
    return [pscustomobject]@{
        RemovedRoots = [string[]]$removed.ToArray()
        ExternalDatabase = $Plan.ExternalDatabase
        ExternalDatabaseDeleted = $externalDeleted
        RetainedPaths = [string[]]$retained.ToArray()
    }
}

function Test-ChatBoxDataRemoved {
    param([Parameter(Mandatory = $true)]$Plan, [switch]$DeleteExternalDatabase)
    Assert-ChatBoxPlanLocations $Plan
    foreach ($path in @($Plan.Roots) + @($Plan.StartupShortcut)) {
        if ($null -ne (Get-ChatBoxPathAttributes $path)) { $path }
    }
    if ($DeleteExternalDatabase -and -not [string]::IsNullOrWhiteSpace($Plan.ExternalDatabase)) {
        foreach ($path in @(Get-ChatBoxDatabaseFiles $Plan.ExternalDatabase)) {
            if ($null -ne (Get-ChatBoxPathAttributes $path)) { $path }
        }
    }
}
