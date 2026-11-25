#Requires -Version 5.1
<#
  Explicit full uninstall for the current Windows user. Normal MSI uninstall and
  major upgrades do not run this script. Only msiexec is elevated; user-data
  deletion always stays in the account that started the uninstaller.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [switch] $Yes,
    [switch] $DeleteExternalDatabase,
    [string] $InstallDirectory,
    [switch] $Worker,
    [string] $ExpectedExternalDatabase = 'none',
    [string] $ExpectedProductCode = 'none'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$script:ChatBoxUpgradeCode = '{204B3F78-64EC-4071-9A66-A535773540EB}'
. (Join-Path $PSScriptRoot 'Uninstall-ChatBoxData.ps1')

function Release-ChatBoxComObject($Value) {
    if ($null -ne $Value -and [Runtime.InteropServices.Marshal]::IsComObject($Value)) {
        [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($Value)
    }
}

function Get-ChatBoxMsiRows($Database, [string] $Query, [string[]] $Columns) {
    $view = $null
    try {
        $view = $Database.OpenView($Query)
        [void] $view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            try {
                $row = [ordered]@{}
                for ($i = 0; $i -lt $Columns.Count; $i++) {
                    $row[$Columns[$i]] = [string] $record.StringData($i + 1)
                }
                [pscustomobject] $row
            }
            finally { Release-ChatBoxComObject $record }
        }
    }
    finally {
        if ($null -ne $view) { [void] $view.Close(); Release-ChatBoxComObject $view }
    }
}

function Get-ChatBoxInstalledProduct([string] $ExpectedDirectory) {
    $installer = $null
    $database = $null
    try {
        $installer = New-Object -ComObject WindowsInstaller.Installer
        $products = @($installer.RelatedProducts($script:ChatBoxUpgradeCode))
        if ($products.Count -eq 0) { return $null }
        if ($products.Count -ne 1) {
            throw 'More than one Chat Box MSI is registered. Resolve the duplicate installation before full uninstall.'
        }
        $code = [string] $products[0]
        if ($code -notmatch '^\{[0-9A-Fa-f-]{36}\}$') { throw 'Invalid MSI product identity.' }
        $cachedMsi = [string] $installer.ProductInfo($code, 'LocalPackage')
        if (-not [IO.File]::Exists($cachedMsi)) {
            throw 'The Windows Installer cache is missing. Repair Chat Box with its matching MSI before uninstalling.'
        }
        $database = $installer.OpenDatabase($cachedMsi, 0)
        $properties = @{}
        foreach ($row in @(Get-ChatBoxMsiRows $database 'SELECT `Property`, `Value` FROM `Property`' @('Name', 'Value'))) {
            $properties[$row.Name] = $row.Value
        }
        if ($properties['UpgradeCode'] -ine $script:ChatBoxUpgradeCode -or $properties['ProductCode'] -ine $code) {
            throw 'The cached MSI does not belong to the registered Chat Box product.'
        }
        # 0.1.1 has an unsafe recursive-uninstall action. Do not invoke it here.
        if ([Version] $properties['ProductVersion'] -lt [Version] '0.1.2') {
            throw 'Chat Box 0.1.1 needs its documented safe upgrade procedure before it can be uninstalled.'
        }
        $files = @(Get-ChatBoxMsiRows $database 'SELECT `File`.`FileName`, `Component`.`ComponentId` FROM `File`, `Component` WHERE `File`.`Component_` = `Component`.`Component`' @('Name', 'ComponentId'))
        $launchers = @($files | Where-Object { ($_.Name -split '\|')[-1] -ieq 'Chatbox.exe' })
        if ($launchers.Count -ne 1) { throw 'The MSI does not contain one identifiable Chatbox.exe component.' }
        $executable = [string] $installer.ComponentPath($code, $launchers[0].ComponentId)
        if (-not [IO.Path]::IsPathRooted($executable) -or [IO.Path]::GetFileName($executable) -ine 'Chatbox.exe') {
            throw 'Windows Installer did not return the installed Chat Box executable path.'
        }
        $directory = [IO.Path]::GetFullPath([IO.Path]::GetDirectoryName($executable)).TrimEnd('\')
        if ($ExpectedDirectory -and $directory -ine [IO.Path]::GetFullPath($ExpectedDirectory).TrimEnd('\')) {
            throw 'This uninstaller is not in the currently registered Chat Box installation. Use the current Start menu entry.'
        }
        $checks = @()
        foreach ($row in @(Get-ChatBoxMsiRows $database 'SELECT `Registry`.`Root`, `Registry`.`Key`, `Registry`.`Name`, `Component`.`Attributes` FROM `Registry`, `Component` WHERE `Registry`.`Component_` = `Component`.`Component`' @('Root', 'Key', 'Name', 'Attributes'))) {
            $root = [int] $row.Root
            if ($root -eq -1) { $root = if ($properties['ALLUSERS'] -eq '1') { 2 } else { 1 } }
            if ($root -eq 0) {
                $root = if ($properties['ALLUSERS'] -eq '1') { 2 } else { 1 }
                $row.Key = 'Software\Classes\' + $row.Key
            }
            if ($root -notin @(1, 2)) { throw 'Unexpected registry hive in the Chat Box MSI.' }
            $key = $row.Key
            foreach ($name in $properties.Keys) { $key = $key.Replace('[' + $name + ']', [string] $properties[$name]) }
            if ($key.Contains('[') -or $row.Name.Contains('[')) { throw 'Cannot safely verify a formatted MSI registry entry.' }
            # '+' means preserve the empty key; it is not a registry value name.
            if ($row.Name -eq '+') { continue }
            $checks += [pscustomobject]@{
                Hive = if ($root -eq 1) { 'CurrentUser' } else { 'LocalMachine' }
                View = if (([int] $row.Attributes -band 256) -ne 0) { 'Registry64' } else { 'Registry32' }
                Key = $key
                Name = $row.Name
                # The app's dedicated protocol key must disappear, including empty subkeys.
                EntireKey = $row.Name -in @('-', '*') -or $key -ieq 'Software\Classes\polarzen-chatbox'
            }
        }
        return [pscustomobject]@{
            ProductCode = $code
            Version = $properties['ProductVersion']
            Directory = $directory
            Executable = $executable
            RegistryChecks = $checks
        }
    }
    finally { Release-ChatBoxComObject $database; Release-ChatBoxComObject $installer }
}

function Get-ChatBoxRegistryRemainders($Product) {
    foreach ($check in $Product.RegistryChecks) {
        $base = $null
        $key = $null
        try {
            $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey(
                [Microsoft.Win32.RegistryHive] $check.Hive,
                [Microsoft.Win32.RegistryView] $check.View)
            $key = $base.OpenSubKey($check.Key, $false)
            if ($null -ne $key -and ($check.EntireKey -or $key.GetValueNames() -contains $check.Name)) {
                "$($check.Hive)\$($check.Key) [$($check.Name)]"
            }
        }
        finally { if ($null -ne $key) { $key.Dispose() }; if ($null -ne $base) { $base.Dispose() } }
    }
}

function Stop-ChatBoxInstalledProcess($Product) {
    if ($null -eq $Product) { return }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name='Chatbox.exe'")) {
        if (-not $process.ExecutablePath) {
            throw 'A Chatbox.exe process could not be identified. Close all Chat Box windows and tray icons, then retry.'
        }
        if ([IO.Path]::GetFullPath($process.ExecutablePath) -ine $Product.Executable) { continue }
        $owner = Invoke-CimMethod -InputObject $process -MethodName GetOwnerSid
        if ($owner.ReturnValue -ne 0 -or $owner.Sid -ne $sid) {
            throw 'Another Windows user is running this Chat Box installation. Ask that user to exit it before uninstalling.'
        }
        $live = Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue
        if ($null -eq $live) { continue }
        [void] $live.CloseMainWindow()
        if ($live.WaitForExit(2500)) { continue }
        # Re-read identity just before stopping; never kill Java/Python by name.
        $again = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.ProcessId)"
        if ($null -eq $again) { continue }
        if ($again.CreationDate -ne $process.CreationDate -or $again.ExecutablePath -ine $Product.Executable) {
            throw 'The running process changed during uninstall. Retry after closing Chat Box.'
        }
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
        if (-not $live.WaitForExit(10000)) { throw 'Chat Box is still running. No local data was deleted.' }
    }
}

function Show-ChatBoxUninstallMessage([string] $Text, [switch] $ErrorMessage) {
    Add-Type -AssemblyName System.Windows.Forms
    $icon = if ($ErrorMessage) { [Windows.Forms.MessageBoxIcon]::Error } else { [Windows.Forms.MessageBoxIcon]::Information }
    [void] [Windows.Forms.MessageBox]::Show($Text, 'Chat Box', [Windows.Forms.MessageBoxButtons]::OK, $icon)
}

function Confirm-ChatBoxUninstall($Plan, $Product) {
    Add-Type -AssemblyName System.Windows.Forms
    $text = "此操作将卸载 Chat Box，并永久删除当前 Windows 用户的：`r`n`r`n会话历史、草稿、附件副本、已保存的密钥、设备绑定和共享服务数据。`r`n`r`n共享服务及登录自启动也会关闭。删除后无法恢复。`r`n`r`n不会删除原始附件、AstrBot 服务端记录或手机上的本地记录。`r`n`r`n是否继续？"
    if ($null -eq $Product) { $text = "没有找到已登记的 Chat Box 安装，将清理本机剩余数据。`r`n`r`n" + $text }
    return [Windows.Forms.MessageBox]::Show($text, '完整卸载 Chat Box',
        [Windows.Forms.MessageBoxButtons]::YesNo, [Windows.Forms.MessageBoxIcon]::Warning,
        [Windows.Forms.MessageBoxDefaultButton]::Button2) -eq [Windows.Forms.DialogResult]::Yes
}

function New-ChatBoxUninstallWorkspace {
    $path = Join-Path ([IO.Path]::GetTempPath()) ('ChatBox-Uninstall-' + [Guid]::NewGuid().ToString('N'))
    $security = New-Object Security.AccessControl.DirectorySecurity
    $security.SetAccessRuleProtection($true, $false)
    $current = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $security.SetOwner($current)
    foreach ($sid in @($current, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl',
            'ContainerInherit, ObjectInherit', 'None', 'Allow')
        [void] $security.AddAccessRule($rule)
    }
    [void] [IO.Directory]::CreateDirectory($path, $security)
    return $path
}

function Invoke-ChatBoxMsiUninstall($Product, [string] $LogPath) {
    $msiexec = Join-Path ([Environment]::GetFolderPath('Windows')) 'System32\msiexec.exe'
    $arguments = "/x $($Product.ProductCode) /passive /norestart /L*v `"$LogPath`""
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    $parameters = @{ FilePath = $msiexec; ArgumentList = $arguments; Wait = $true; PassThru = $true }
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { $parameters.Verb = 'RunAs' }
    $process = Start-Process @parameters
    if ($process.ExitCode -notin @(0, 3010)) {
        throw "Windows Installer returned $($process.ExitCode). Local history and credentials have not been deleted. Log: $LogPath"
    }
    return [int] $process.ExitCode
}

function Invoke-ChatBoxFullUninstall {
    if ($env:OS -ne 'Windows_NT') { throw 'This uninstaller is for Windows.' }
    $expectedDirectory = if ($InstallDirectory) { [IO.Path]::GetFullPath($InstallDirectory) }
        elseif ([IO.File]::Exists((Join-Path $PSScriptRoot 'Chatbox.exe'))) { $PSScriptRoot }
        else { $null } # The separately distributed cleanup ZIP resolves the MSI by UpgradeCode.
    $product = Get-ChatBoxInstalledProduct $expectedDirectory
    $plan = Get-ChatBoxDataPlan
    Assert-ChatBoxDataPlan -Plan $plan
    if ($Worker) {
        $actualProduct = if ($null -ne $product) { $product.ProductCode } else { 'none' }
        $actualExternal = if ($plan.ExternalDatabase) { $plan.ExternalDatabase } else { 'none' }
        if ($actualProduct -ine $ExpectedProductCode -or $actualExternal -ine $ExpectedExternalDatabase) {
            throw 'The installation or external database changed after confirmation. No local data was deleted; run uninstall again.'
        }
    }
    if (-not $expectedDirectory) { $expectedDirectory = if ($null -ne $product) { $product.Directory } else { $PSScriptRoot } }
    if ($WhatIfPreference) {
        [pscustomobject]@{
            Operation = 'Uninstall Chat Box and erase this Windows user''s local data'
            ProductCode = if ($null -ne $product) { $product.ProductCode } else { $null }
            InstallDirectory = if ($null -ne $product) { $product.Directory } else { $null }
            DataDirectories = @($plan.Roots)
            StartupShortcut = $plan.StartupShortcut
            ExternalDatabase = $plan.ExternalDatabase
            DeleteExternalDatabase = [bool] $DeleteExternalDatabase
        } | ConvertTo-Json -Depth 5 | Write-Host
        return 0
    }
    if (-not $Yes -and -not (Confirm-ChatBoxUninstall $plan $product)) { return 2 }
    $deleteExternal = [bool] $DeleteExternalDatabase
    if (-not $Yes -and $plan.ExternalDatabase -and -not $deleteExternal) {
        Add-Type -AssemblyName System.Windows.Forms
        $question = "Chat Box 使用了这个外置共享数据库：`r`n`r`n$($plan.ExternalDatabase)`r`n`r`n是否一并永久删除其中的共享会话和设备绑定？`r`n如果其他独立服务也在使用它，请选择否以保留。只删除数据库及其辅助文件，不删除所在文件夹。"
        $deleteExternal = [Windows.Forms.MessageBox]::Show($question, '外置共享数据库',
            [Windows.Forms.MessageBoxButtons]::YesNo, [Windows.Forms.MessageBoxIcon]::Warning,
            [Windows.Forms.MessageBoxDefaultButton]::Button2) -eq [Windows.Forms.DialogResult]::Yes
    }

    if (-not $Worker) {
        $workspace = New-ChatBoxUninstallWorkspace
        $exitCode = 1
        try {
            foreach ($file in @('Uninstall-ChatBox.ps1', 'Uninstall-ChatBoxData.ps1')) {
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $workspace $file)
            }
            # Both parent and worker must leave the MSI directory before it is removed.
            Set-Location -LiteralPath $workspace
            $powershell = Join-Path ([Environment]::GetFolderPath('Windows')) 'System32\WindowsPowerShell\v1.0\powershell.exe'
            $arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
                ('"' + (Join-Path $workspace 'Uninstall-ChatBox.ps1') + '"'), '-Yes', '-Worker',
                '-InstallDirectory', ('"' + $expectedDirectory + '"'),
                '-ExpectedProductCode', $(if ($null -ne $product) { $product.ProductCode } else { 'none' }),
                '-ExpectedExternalDatabase', ('"' + $(if ($plan.ExternalDatabase) { $plan.ExternalDatabase } else { 'none' }) + '"'))
            if ($deleteExternal) { $arguments += '-DeleteExternalDatabase' }
            $child = Start-Process -FilePath $powershell -ArgumentList $arguments -WorkingDirectory $workspace -NoNewWindow -Wait -PassThru
            $exitCode = [int] $child.ExitCode
            if (-not $Yes) {
                if ($exitCode -eq 0) {
                    $message = 'Chat Box 已卸载，当前 Windows 用户的本机数据和安装包登记项已清理。'
                    if ($plan.ExternalDatabase -and -not $deleteExternal) { $message += "`r`n`r`n按你的选择保留了外置共享数据库：`r`n" + $plan.ExternalDatabase }
                    Show-ChatBoxUninstallMessage $message
                }
                elseif ($exitCode -eq 3010) { Show-ChatBoxUninstallMessage "本机数据已清理。Windows Installer 要求重启电脑，重启后再确认程序文件已移除。`r`n日志：$workspace" }
                else { Show-ChatBoxUninstallMessage "完整卸载未完成。请查看窗口中的错误信息，并保留以下日志以便排查：`r`n$workspace" -ErrorMessage }
            }
            return $exitCode
        }
        finally {
            Set-Location -LiteralPath ([Environment]::GetFolderPath('Windows'))
            if ($exitCode -eq 0) {
                # Only this invocation's fresh private workspace is removed.
                Remove-Item -LiteralPath $workspace -Recurse -Force
            }
            else { Write-Host "Uninstall diagnostics and retry scripts: $workspace" }
        }
    }

    Write-Host 'Stopping this installation and the current user''s Chat Box shared service...'
    Stop-ChatBoxInstalledProcess $product
    Stop-ChatBoxLocalServices -Plan $plan
    $msiExit = 0
    if ($null -ne $product) {
        $msiExit = Invoke-ChatBoxMsiUninstall $product (Join-Path $PSScriptRoot 'uninstall.log')
        if ($null -ne (Get-ChatBoxInstalledProduct)) { throw 'Windows Installer still reports Chat Box as installed. Local data was preserved.' }
        $registryRemainders = @(Get-ChatBoxRegistryRemainders $product)
        if ($registryRemainders.Count -ne 0 -and $msiExit -ne 3010) {
            throw ('MSI registry entries remain; local data was preserved: ' + ($registryRemainders -join '; '))
        }
    }
    $result = Remove-ChatBoxLocalData -Plan $plan -DeleteExternalDatabase:$deleteExternal
    $remaining = @(Test-ChatBoxDataRemoved -Plan $plan -DeleteExternalDatabase:$deleteExternal)
    if ($remaining.Count -ne 0) { throw ('Some selected Chat Box data could not be removed: ' + ($remaining -join '; ')) }
    if ($null -ne $product -and [IO.File]::Exists($product.Executable) -and $msiExit -ne 3010) {
        throw 'The installed Chatbox.exe remains. Full uninstall is not complete.'
    }
    Write-Host 'Chat Box MSI registration, selected local history, credentials, caches and shared runtime have been removed.'
    foreach ($path in @($result.RetainedPaths)) { Write-Host "Retained by request: $path" }
    if ($msiExit -eq 3010) { Write-Host 'Windows requires a restart to finish removing installed files.' }
    return $msiExit
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        # Functions write diagnostics using Write-Host, leaving only this exit code on the pipeline.
        $code = Invoke-ChatBoxFullUninstall
        exit [int] $code
    }
    catch {
        Write-Host ('Full uninstall did not complete: ' + $_.Exception.Message) -ForegroundColor Red
        if (-not $Yes -and -not $WhatIfPreference) { Show-ChatBoxUninstallMessage $_.Exception.Message -ErrorMessage }
        exit 1
    }
}
