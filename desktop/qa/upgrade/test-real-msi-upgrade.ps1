[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $CandidateMsiPath,
    [Parameter(Mandatory = $true)][string] $InstallDir,
    [Parameter(Mandatory = $true)][string] $CandidateProductCode,
    [Parameter(Mandatory = $true)][string] $UpgradeCode,
    [Parameter(Mandatory = $true)][string] $CandidateRoot,
    [Parameter(Mandatory = $true)][string] $TestRoot
)

$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:OS -ne 'Windows_NT') {
    throw 'The real MSI upgrade test is restricted to a GitHub-hosted Windows Actions runner.'
}

$runnerTemp = [IO.Path]::GetFullPath($env:RUNNER_TEMP).TrimEnd('\')
$testRootPath = [IO.Path]::GetFullPath($TestRoot).TrimEnd('\')
$installDirPath = [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')
$CandidateRootPath = (Resolve-Path -LiteralPath $CandidateRoot).Path
$runnerPrefix = $runnerTemp + '\'
$testRootPrefix = $testRootPath + '\'
if (-not $testRootPath.StartsWith($runnerPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $CandidateRootPath.StartsWith($runnerPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not (Split-Path -Leaf $testRootPath).StartsWith('chatbox-upgrade-regression-', [StringComparison]::OrdinalIgnoreCase) -or
    -not $installDirPath.StartsWith($testRootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not ([IO.Path]::GetRelativePath($testRootPath, $installDirPath).Equals('install', [StringComparison]::OrdinalIgnoreCase))) {
    throw 'The synthetic candidate and true-upgrade install directory must be the owned install child under the isolated RUNNER_TEMP test root.'
}

$candidateMsi = (Resolve-Path -LiteralPath $CandidateMsiPath).Path
$summaryPath = Join-Path $testRootPath 'real-upgrade-summary.log'
$syntheticUninstallLog = Join-Path $testRootPath 'synthetic-candidate-uninstall.log'
$legacyInstallLog = Join-Path $testRootPath 'published-v0.1.5-install.log'
$candidateUpgradeLog = Join-Path $testRootPath 'published-v0.1.5-to-candidate-upgrade.log'
$releaseDir = Join-Path $testRootPath 'real-upgrade-release'
$releaseMsiPath = Join-Path $releaseDir 'Chatbox-0.1.5.msi'
$releaseSha256 = 'E5F0508D74C6E90F1863CDA41953C53548EF137D7A4A2725B99CBB4EFDF48856'
$fixtureRunRoot = $null
$safeServiceLog = $null
$oldApp = $null
$candidateFirstApp = $null
$candidateSecondApp = $null
$candidateThirdApp = $null
$oldLocalAppData = $env:LOCALAPPDATA
$oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$installer = $null
$candidateDatabase = $null
$legacyDatabase = $null
$candidateUiAccepted = $false
$fixtureProps = @{}

function Write-Evidence([string] $Line) {
    Add-Content -LiteralPath $summaryPath -Value $Line -Encoding UTF8
    Write-Host $Line
}

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Get-CheckoutToken {
    foreach ($variableName in @('GH_TOKEN', 'GITHUB_TOKEN')) {
        $token = [Environment]::GetEnvironmentVariable($variableName)
        if (-not [string]::IsNullOrWhiteSpace($token)) { return $token.Trim() }
    }

    # actions/checkout persists its existing token as a local Git extraheader.
    # Decode it in memory only; never write it to output or pass it as a CLI argument.
    $checkoutHeader = & git config --local --get http.https://github.com/.extraheader 2>$null
    if ($LASTEXITCODE -eq 0 -and $checkoutHeader -match '^\s*Authorization:\s*Basic\s+(\S+)\s*$') {
        try {
            $decoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1]))
            $separator = $decoded.IndexOf(':')
            if ($separator -gt 0 -and $decoded.Substring(0, $separator) -eq 'x-access-token') {
                $token = $decoded.Substring($separator + 1)
                if (-not [string]::IsNullOrWhiteSpace($token)) { return $token }
            }
        }
        catch { }
    }
    elseif ($LASTEXITCODE -eq 0 -and $checkoutHeader -match '^\s*Authorization:\s*Bearer\s+(\S+)\s*$') {
        return $Matches[1]
    }
    throw 'No GH_TOKEN, GITHUB_TOKEN, or actions/checkout repository credential is available for the private v0.1.5 release asset.'
}

function Get-PinnedReleaseMsi([string] $Destination) {
    $priorGhToken = $env:GH_TOKEN
    $priorGithubToken = $env:GITHUB_TOKEN
    try {
        if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN) -and [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) {
            $env:GH_TOKEN = Get-CheckoutToken
        }
        $gh = Get-Command gh -CommandType Application -ErrorAction Stop
        & $gh.Source release download v0.1.5 --repo Polarzen/chatbox --pattern 'Chatbox-0.1.5.msi' --dir $releaseDir *> $null
        if ($LASTEXITCODE -ne 0) { throw "GitHub CLI release download failed with exit code $LASTEXITCODE." }
        if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) { throw 'GitHub CLI did not download the pinned release MSI asset.' }
    }
    finally {
        $env:GH_TOKEN = $priorGhToken
        $env:GITHUB_TOKEN = $priorGithubToken
    }
}

function Get-FileVersion([string] $Path) {
    $version = [Diagnostics.FileVersionInfo]::GetVersionInfo($Path)
    return [Version]::new($version.FileMajorPart, $version.FileMinorPart, $version.FileBuildPart)
}

function ConvertTo-ProductVersion([string] $Value) {
    $version = [Version]$Value
    return [Version]::new($version.Major, $version.Minor, $version.Build)
}

function Get-MsiProperty($Database, [string] $Name) {
    $view = $null
    try {
        $view = $Database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property``='$Name'")
        [void] $view.Execute()
        $record = $view.Fetch()
        if ($null -eq $record) { throw "MSI property '$Name' was not found." }
        return $record.StringData(1)
    }
    finally {
        if ($null -ne $view) { [void] $view.Close(); [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) }
    }
}

function Get-RelatedProducts($Installer, [string] $Code) {
    return @($Installer.RelatedProducts($Code))
}

function Invoke-Msi([string] $Path, [string] $Directory, [string] $LogPath) {
    $arguments = "/i `"$Path`" /qn INSTALLDIR=`"$Directory`" /L*v `"$LogPath`""
    $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $arguments -Wait -PassThru -NoNewWindow
    if ($process.ExitCode -ne 0) { throw "msiexec install failed with exit code $($process.ExitCode). Log: $LogPath" }
}

function Invoke-ProductUninstall([string] $ProductCode, [string] $LogPath) {
    $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList "/x `"$ProductCode`" /qn /L*v `"$LogPath`"" -Wait -PassThru -NoNewWindow
    if ($process.ExitCode -ne 0) { throw "msiexec uninstall failed with exit code $($process.ExitCode). Log: $LogPath" }
}

function Get-StartMenuShortcut([string] $Executable) {
    $programs = @(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::Programs),
        [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonPrograms)
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) }
    $shell = New-Object -ComObject WScript.Shell
    foreach ($directory in $programs) {
        foreach ($shortcutFile in Get-ChildItem -LiteralPath $directory -Filter '*.lnk' -File -Recurse -ErrorAction SilentlyContinue) {
            $shortcut = $shell.CreateShortcut($shortcutFile.FullName)
            if ($shortcut.TargetPath -and [IO.Path]::GetFullPath($shortcut.TargetPath).Equals($Executable, [StringComparison]::OrdinalIgnoreCase)) {
                return $shortcutFile.FullName
            }
        }
    }
    return $null
}

function Start-InstalledApp([string] $Executable, [bool] $RequireStartMenuShortcut, [string] $LocalAppData, [string] $TrustStore) {
    $entry = $Executable
    if ($RequireStartMenuShortcut) {
        $entry = Get-StartMenuShortcut $Executable
        if (-not $entry) { throw 'Candidate MSI did not install a Start Menu shortcut targeting its installed executable.' }
    }
    if (@(Get-Process -Name Chatbox -ErrorAction SilentlyContinue).Count -ne 0) { throw 'A Chatbox process is already running on the isolated hosted runner.' }

    $startTime = Get-Date
    $env:LOCALAPPDATA = $LocalAppData
    $env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStore=$TrustStore -Djavax.net.ssl.trustStoreType=JKS"
    try { [void] (Start-Process -FilePath $entry -PassThru) }
    finally {
        $env:LOCALAPPDATA = $oldLocalAppData
        $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        # jpackage can leave both a launcher and a window-owning child named Chatbox.
        $app = Get-Process -Name Chatbox -ErrorAction SilentlyContinue | Where-Object {
            $_.StartTime -ge $startTime -and $_.MainWindowHandle -ne [IntPtr]::Zero
        } | Select-Object -First 1
        if ($app) {
            $app.Refresh()
            if ($app.MainWindowHandle -ne [IntPtr]::Zero) {
                if (-not $app.Path -or -not [IO.Path]::GetFullPath($app.Path).Equals([IO.Path]::GetFullPath($Executable), [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'The launched Chatbox process is not the installed executable under test.'
                }
                return $app
            }
        }
        Start-Sleep -Milliseconds 250
    }
    throw 'Installed Chatbox did not create a visible window within 45 seconds.'
}

function Stop-App($Process) {
    if ($null -eq $Process) { return }
    $Process.Refresh()
    if ($Process.HasExited) { return }
    if ($Process.MainWindowHandle -ne [IntPtr]::Zero) {
        [void] [RealUpgradeNative]::PostMessage($Process.MainWindowHandle, [RealUpgradeNative]::WmClose, [IntPtr]::Zero, [IntPtr]::Zero)
    }
    if (-not $Process.WaitForExit(20000)) { Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue }
}

function Stop-RunnerChatboxProcesses {
    foreach ($process in @(Get-Process -Name Chatbox -ErrorAction SilentlyContinue)) { Stop-App $process }
}

function Get-ServiceLines {
    if (-not $safeServiceLog -or -not (Test-Path -LiteralPath $safeServiceLog)) { return @() }
    return @(Get-Content -LiteralPath $safeServiceLog)
}

function Wait-ServiceRequest([string] $Needle, [int] $AfterLine, [int] $TimeoutSeconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $lines = Get-ServiceLines
        $match = $lines | Select-Object -Skip $AfterLine | Where-Object { $_ -match $Needle } | Select-Object -First 1
        if ($match) { return $match }
        Start-Sleep -Milliseconds 400
    }
    throw "Local fixture did not record the expected safe request: $Needle"
}

function Get-History([string] $Path) {
    return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json -Depth 100
}

function Assert-SeedHistory($History, [string] $Username, [string] $SessionAId, [string] $SessionBId) {
    $profileAId = 'manual:https://127.0.0.1:18443'
    $profileBId = 'manual:https://127.0.0.1:18444'
    if ($History.schemaVersion -ne 2 -or $History.username -ne $Username -or $History.activeProfileId -ne $profileAId) {
        throw 'The 0.1.5-era history schema, username, or selected profile was not retained.'
    }
    $profileA = @($History.profiles) | Where-Object id -eq $profileAId | Select-Object -First 1
    $profileB = @($History.profiles) | Where-Object id -eq $profileBId | Select-Object -First 1
    if (-not $profileA -or -not $profileB -or $profileA.address -ne 'https://127.0.0.1:18443' -or $profileB.address -ne 'https://127.0.0.1:18444' -or
        $profileA.kind -ne 'manual' -or $profileB.kind -ne 'manual' -or $profileA.offlineOnly -or $profileB.offlineOnly -or
        $profileA.currentSession -ne $SessionAId -or $profileB.currentSession -ne $SessionBId) {
        throw 'The two legacy service configurations or their active sessions were not retained.'
    }
    $sessionA = @($profileA.sessions) | Where-Object id -eq $SessionAId | Select-Object -First 1
    $sessionB = @($profileB.sessions) | Where-Object id -eq $SessionBId | Select-Object -First 1
    if (-not $sessionA -or -not $sessionB -or @($sessionA.entries).Count -lt 2 -or @($sessionB.entries).Count -lt 2) {
        throw 'A seeded conversation was lost during upgrade.'
    }
    $entryA = @($sessionA.entries)[0]
    $entryB = @($sessionB.entries)[0]
    if ($entryA.question -ne '这张图里的色块适合做主视觉吗？' -or
        $entryA.answer -ne '把画面主体留在左侧，右侧用一行标题和短说明，层级会更清楚。' -or
        @($entryA.replyImages | Where-Object attachmentId -eq 'reply-image-a').Count -ne 1 -or
        $entryB.question -ne '帮我把这份产品说明整理成一句话。' -or
        $entryB.answer -ne '这款轻巧的桌面音箱让清晰人声和简洁外观同时融入日常空间。' -or
        @($entryB.replyImages | Where-Object attachmentId -eq 'reply-image-b').Count -ne 1) {
        throw 'Legacy message content or saved reply-image references were not retained.'
    }
}

function Get-ProfileHash([string] $ProfileId) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($ProfileId))).ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-LegacyCredentialName([string] $ProfileId) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($ProfileId))
        $name = [Convert]::ToBase64String($digest).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        return "credential-$name.bin"
    }
    finally { $sha.Dispose() }
}

function Assert-LegacyKey([string] $AppData, [string] $ProfileId, [string] $ExpectedSecret) {
    $metadataPath = Join-Path $AppData "named-keys-$(Get-ProfileHash $ProfileId).json"
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) { throw 'Candidate did not persist legacy key metadata.' }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $keys = @($metadata.keys)
    if ($metadata.schemaVersion -ne 1 -or -not $metadata.migrated -or $metadata.activeId -ne 'legacy' -or $keys.Count -ne 1 -or
        $keys[0].id -ne 'legacy' -or $keys[0].name -ne '原有密钥' -or @($metadata.pendingDeletes).Count -ne 0) {
        throw 'Candidate key metadata does not represent the migrated legacy credential.'
    }
    $credentialPath = Join-Path (Join-Path $AppData 'credentials') (Get-LegacyCredentialName $ProfileId)
    if (-not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) { throw 'The original DPAPI credential blob was not preserved.' }
    $entropy = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($ProfileId))
    $protected = [IO.File]::ReadAllBytes($credentialPath)
    $clear = $null
    try {
        $clear = [Security.Cryptography.ProtectedData]::Unprotect($protected, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        if ([Text.Encoding]::UTF8.GetString($clear) -cne $ExpectedSecret) { throw 'The migrated current-user DPAPI credential did not decrypt correctly.' }
    }
    finally {
        [Array]::Clear($protected, 0, $protected.Length)
        [Array]::Clear($entropy, 0, $entropy.Length)
        if ($null -ne $clear) { [Array]::Clear($clear, 0, $clear.Length) }
    }
    return [pscustomobject]@{ MetadataPath = $metadataPath; Hash = Get-Sha256 $metadataPath }
}

function Get-ReplyImagePath([string] $AppData, [string] $ProfileId, [string] $AttachmentId) {
    $key = "$ProfileId$([char]0)$AttachmentId"
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $name = ([Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($key)))).ToLowerInvariant() + '.image' }
    finally { $sha.Dispose() }
    return Join-Path (Join-Path $AppData 'reply-images') $name
}

function Wait-ReplyImage([string] $AppData, [string] $ProfileId, [string] $AttachmentId) {
    $path = Get-ReplyImagePath $AppData $ProfileId $AttachmentId
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Test-Path -LiteralPath $path -PathType Leaf) -and (Get-Item -LiteralPath $path).Length -gt 100) { return (Get-Sha256 $path) }
        Start-Sleep -Milliseconds 300
    }
    throw 'The installed app did not persist the fixture reply image.'
}

function Get-UIControls($Process) {
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($Process.MainWindowHandle)
    $elements = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
    $controls = @()
    for ($index = 0; $index -lt $elements.Count; $index++) {
        $element = $elements.Item($index)
        try {
            $current = $element.Current
            $controls += [pscustomobject]@{ Element = $element; Name = [string]$current.Name; Type = [string]$current.ControlType.ProgrammaticName; Enabled = [bool]$current.IsEnabled }
        }
        catch { }
    }
    return ,$controls
}

function Find-UIControl($Process, [string] $Type, [string[]] $AcceptedNames, [int] $TimeoutSeconds = 10) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $controls = Get-UIControls $Process
        $match = $controls | Where-Object {
            $control = $_
            $control.Type -eq $Type -and $control.Enabled -and
                ($AcceptedNames | Where-Object { $_ -and $control.Name.Contains($_) } | Select-Object -First 1)
        } | Select-Object -First 1
        if ($match) { return $match }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'UIA_UNAVAILABLE: required app controls were not exposed.'
}

function Find-Composer($Process) {
    $controls = Get-UIControls $Process
    $edit = $controls | Where-Object { $_.Type -eq 'ControlType.Edit' -and $_.Enabled -and $_.Name -match '输入消息|添加图片说明' } | Select-Object -First 1
    if ($edit) { return $edit }
    $edits = @($controls | Where-Object { $_.Type -eq 'ControlType.Edit' -and $_.Enabled })
    if ($edits.Count -eq 1) { return $edits[0] }
    throw 'UIA_UNAVAILABLE: the app composer was not exposed.'
}

function Set-UIValue($Control, [string] $Value) {
    try { $Control.Element.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue($Value) }
    catch {
        try {
            $Control.Element.SetFocus()
            [System.Windows.Forms.SendKeys]::SendWait('^a')
            [System.Windows.Forms.SendKeys]::SendWait($Value)
        }
        catch { throw 'UIA_UNAVAILABLE: the app text field was not writable.' }
    }
}

function Invoke-UIButton($Control) {
    try { $Control.Element.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke() }
    catch { throw 'UIA_UNAVAILABLE: the app button was not invokable.' }
}

function Get-ImageDialogControls([int] $ProcessId) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
            [System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition)
        for ($index = 0; $index -lt $windows.Count; $index++) {
            $window = $windows.Item($index)
            try {
                if ($window.Current.ProcessId -ne $ProcessId -or $window.Current.Name -notlike '*选择图片*') { continue }
                $elements = $window.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
                $controls = @()
                for ($child = 0; $child -lt $elements.Count; $child++) {
                    $element = $elements.Item($child)
                    try {
                        $current = $element.Current
                        $controls += [pscustomobject]@{ Element = $element; Name = [string]$current.Name; Type = [string]$current.ControlType.ProgrammaticName }
                    }
                    catch { }
                }
                return ,$controls
            }
            catch { }
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'UIA_UNAVAILABLE: the installed app image chooser was not exposed.'
}

function Test-CandidateUIA($Process) {
    try {
        Add-Type -AssemblyName System.Windows.Forms
        Add-Type -AssemblyName UIAutomationClient
        Add-Type -AssemblyName UIAutomationTypes
        $null = Find-Composer $Process
        $null = Find-UIControl $Process 'ControlType.Button' @('发送')
        $null = Find-UIControl $Process 'ControlType.Button' @('选择 PNG/JPEG', '选择图片', '＋', '+')
        return $true
    }
    catch { return $false }
}

function Invoke-CandidateUI($Process, [string] $ImagePath, [int] $StartAt) {
    $input = Find-Composer $Process
    Set-UIValue $input 'Hosted candidate chat acceptance.'
    Invoke-UIButton (Find-UIControl $Process 'ControlType.Button' @('发送'))
    Wait-ServiceRequest 'profile=A endpoint=POST /api/v1/chat key=old_key session=[0-9a-fA-F-]{36} result=200' $StartAt
    Wait-ServiceRequest 'profile=A endpoint=GET /api/v1/file key=old_key session=- result=200' $StartAt

    $choose = Find-UIControl $Process 'ControlType.Button' @('选择 PNG/JPEG', '选择图片', '＋', '+')
    Invoke-UIButton $choose
    $dialog = Get-ImageDialogControls $Process.Id
    $fileName = @($dialog | Where-Object Type -eq 'ControlType.Edit' | Select-Object -Last 1)[0]
    $open = $dialog | Where-Object { $_.Type -eq 'ControlType.Button' -and $_.Name -match '^(Open|打开|选择)$' } | Select-Object -First 1
    if (-not $fileName -or -not $open) { throw 'Windows UI Automation did not expose the installed image chooser controls.' }
    Set-UIValue $fileName $ImagePath
    Invoke-UIButton $open
    Set-UIValue (Find-Composer $Process) 'Hosted candidate image acceptance.'
    $imageStartAt = (Get-ServiceLines).Count
    Invoke-UIButton (Find-UIControl $Process 'ControlType.Button' @('发送'))
    Wait-ServiceRequest 'profile=A endpoint=POST /api/v1/file key=old_key session=- result=200' $imageStartAt
    Wait-ServiceRequest 'profile=A endpoint=POST /api/v1/chat key=old_key session=[0-9a-fA-F-]{36} result=200' $imageStartAt
    Wait-ServiceRequest 'profile=A endpoint=GET /api/v1/file key=old_key session=- result=200' $imageStartAt
    return $true
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class RealUpgradeNative {
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);
    public const uint WmClose = 0x0010;
}
'@

try {
    $candidateMsiHash = Get-Sha256 $candidateMsi
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $candidateDatabase = $installer.OpenDatabase($candidateMsi, 0)
    $candidateVersion = ConvertTo-ProductVersion (Get-MsiProperty $candidateDatabase 'ProductVersion')
    if ((Get-MsiProperty $candidateDatabase 'ProductCode') -ne $CandidateProductCode -or
        (Get-MsiProperty $candidateDatabase 'UpgradeCode') -ne $UpgradeCode -or $candidateVersion -le [Version]'0.1.5') {
        throw 'The built candidate MSI product identity or version does not match the synthetic regression install.'
    }
    [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($candidateDatabase)
    $candidateDatabase = $null

    $registered = @(Get-RelatedProducts $installer $UpgradeCode)
    if ($registered.Count -ne 1 -or $registered[0] -ne $CandidateProductCode) {
        throw 'Refusing to uninstall: the only related product is not the synthetic regression candidate owned by this test.'
    }
    $registeredInstallDir = $installer.ProductInfo($CandidateProductCode, 'InstallLocation')
    if ($registeredInstallDir -and -not [IO.Path]::GetFullPath($registeredInstallDir).TrimEnd('\').Equals($installDirPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to uninstall: Windows Installer reports a different installation directory.'
    }
    $syntheticExe = Join-Path $installDirPath 'Chatbox.exe'
    if (-not (Test-Path -LiteralPath $syntheticExe -PathType Leaf) -or (Get-FileVersion $syntheticExe) -ne $candidateVersion) {
        throw 'Refusing to uninstall: the runner-owned install directory does not contain this candidate MSI executable.'
    }
    if (@(Get-Process -Name Chatbox -ErrorAction SilentlyContinue).Count -ne 0) { throw 'Refusing to uninstall while Chatbox is running.' }
    Invoke-ProductUninstall $CandidateProductCode $syntheticUninstallLog
    if (@(Get-RelatedProducts $installer $UpgradeCode).Count -ne 0) { throw 'The owned synthetic candidate product remains registered after uninstall.' }
    Write-Evidence "Removed only the synthetic regression candidate product $CandidateProductCode from the isolated runner-temp install directory."
    Write-Evidence "Built candidate MSI SHA-256: $candidateMsiHash"

    New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
    Get-PinnedReleaseMsi $releaseMsiPath
    $releaseHash = Get-Sha256 $releaseMsiPath
    if ($releaseHash -ne $releaseSha256) { throw "Published v0.1.5 MSI SHA-256 mismatch: $releaseHash" }
    $legacyDatabase = $installer.OpenDatabase($releaseMsiPath, 0)
    if ([Version](Get-MsiProperty $legacyDatabase 'ProductVersion') -ne [Version]'0.1.5' -or
        (Get-MsiProperty $legacyDatabase 'UpgradeCode') -ne $UpgradeCode) {
        throw 'The pinned published MSI metadata does not match v0.1.5 and the Chatbox upgrade code.'
    }
    [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($legacyDatabase)
    $legacyDatabase = $null
    Invoke-Msi $releaseMsiPath $installDirPath $legacyInstallLog
    $oldExe = Join-Path $installDirPath 'Chatbox.exe'
    if (-not (Test-Path -LiteralPath $oldExe -PathType Leaf) -or (Get-FileVersion $oldExe) -ne [Version]'0.1.5') {
        throw 'The pinned official MSI did not install the expected v0.1.5 executable.'
    }
    Write-Evidence "Published v0.1.5 MSI SHA-256 verified: $releaseHash; installed in the same owned directory."

    $fixtureScript = Join-Path $PSScriptRoot '..\fixture\Prepare-Fixture.ps1'
    $fixtureOutput = @(& $fixtureScript -ScratchRoot (Join-Path $testRootPath 'real-fixture-work'))
    if ($LASTEXITCODE -ne 0) { throw 'Existing isolated QA fixture preparation failed.' }
    $runRootLine = $fixtureOutput | Where-Object { $_ -like 'Run root: *' } | Select-Object -First 1
    if (-not $runRootLine) { throw 'Existing isolated QA fixture did not report its run directory.' }
    $fixtureRunRoot = $runRootLine.Substring('Run root: '.Length)
    foreach ($line in $fixtureOutput) { Write-Host $line }
    $privateConfig = Join-Path $fixtureRunRoot 'private\fixture-private.properties'
    foreach ($line in [IO.File]::ReadAllLines($privateConfig, [Text.Encoding]::UTF8)) {
        if ($line -match '^([^=]+)=(.*)$') { $fixtureProps[$Matches[1]] = $Matches[2] }
    }
    $localAppData = Join-Path $fixtureRunRoot 'localappdata'
    $appData = Join-Path $localAppData 'Chatbox'
    $historyPath = Join-Path $appData 'history.json'
    $profileId = 'manual:' + $fixtureProps['base.a']
    $sessionA = $fixtureProps['session.a']
    $sessionB = $fixtureProps['session.b']
    $username = $fixtureProps['username']
    $oldSecret = $fixtureProps['token.a.original']
    Assert-SeedHistory (Get-History $historyPath) $username $sessionA $sessionB

    $fixtureCert = Join-Path $fixtureRunRoot 'tls\fixture-server.cer'
    $fixtureTrust = Join-Path $fixtureRunRoot 'tls\installed-app-trust.jks'
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    & $keytool -importcert -noprompt -alias chatbox-upgrade-fixture -file $fixtureCert -keystore $fixtureTrust -storetype JKS -storepass changeit 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the isolated truststore for installed-app launch.' }
    $safeServiceLog = Join-Path $fixtureRunRoot 'private\service.log'

    $oldStartAt = (Get-ServiceLines).Count
    try {
        $oldApp = Start-InstalledApp $oldExe $false $localAppData $fixtureTrust
        Wait-ServiceRequest 'profile=A endpoint=GET /api/v1/chat/sessions key=old_key session=- result=200' $oldStartAt
        Write-Evidence 'v0.1.5 installed executable launched through its normal entry point and authenticated the preseeded current-user DPAPI key.'
    }
    catch {
        Write-Evidence 'v0.1.5 normal-entry launch/authentication was unavailable; the fixture seed is preseeded state and is not counted as old-app sends.'
    }
    finally {
        Stop-App $oldApp
        $oldApp = $null
        Stop-RunnerChatboxProcesses
    }
    Assert-SeedHistory (Get-History $historyPath) $username $sessionA $sessionB

    $linesBeforeRealUpgrade = (Get-ServiceLines).Count
    Invoke-Msi $candidateMsi $installDirPath $candidateUpgradeLog
    $relatedAfterUpgrade = @(Get-RelatedProducts $installer $UpgradeCode)
    if ($relatedAfterUpgrade.Count -ne 1 -or $relatedAfterUpgrade[0] -ne $CandidateProductCode) {
        throw 'The built candidate MSI did not replace the published v0.1.5 product.'
    }
    $candidateExe = Join-Path $installDirPath 'Chatbox.exe'
    if (-not (Test-Path -LiteralPath $candidateExe -PathType Leaf) -or (Get-FileVersion $candidateExe) -ne $candidateVersion) {
        throw 'The same-path candidate upgrade did not install its expected executable.'
    }
    foreach ($payload in @(
        @{ Name = 'Chatbox.exe'; RelativePath = 'Chatbox.exe' },
        @{ Name = 'jvm.dll'; RelativePath = 'runtime\bin\server\jvm.dll' },
        @{ Name = 'java.dll'; RelativePath = 'runtime\bin\java.dll' }
    )) {
        $sourceFiles = @(Get-ChildItem -LiteralPath $CandidateRootPath -Recurse -File -Filter $payload.Name)
        $installedFile = Join-Path $installDirPath $payload.RelativePath
        if ($sourceFiles.Count -ne 1 -or -not (Test-Path -LiteralPath $installedFile -PathType Leaf) -or
            (Get-Sha256 $sourceFiles[0].FullName) -ne (Get-Sha256 $installedFile)) {
            throw "Installed candidate payload does not byte-match the built MSI: $($payload.Name)"
        }
    }
    Write-Evidence "TRUE MSI upgrade passed: v0.1.5 to candidate $candidateVersion in the same INSTALLDIR."

    $firstStartAt = (Get-ServiceLines).Count
    $candidateFirstApp = Start-InstalledApp $candidateExe $true $localAppData $fixtureTrust
    Write-Evidence "Start Menu process: $($candidateFirstApp.Path) version $(Get-FileVersion $candidateFirstApp.Path), SHA-256 $(Get-Sha256 $candidateFirstApp.Path)."
    Wait-ServiceRequest 'profile=A endpoint=GET /api/v1/chat/sessions key=old_key session=- result=200' $firstStartAt
    $firstKeyState = Assert-LegacyKey $appData $profileId $oldSecret
    Assert-SeedHistory (Get-History $historyPath) $username $sessionA $sessionB
    Write-Evidence 'Candidate Start Menu launch 1 preserved both configurations, seed conversations and image references; legacy key metadata and DPAPI decryption passed.'
    Stop-App $candidateFirstApp
    $candidateFirstApp = $null
    Stop-RunnerChatboxProcesses

    $secondStartAt = (Get-ServiceLines).Count
    $candidateSecondApp = Start-InstalledApp $candidateExe $true $localAppData $fixtureTrust
    Wait-ServiceRequest 'profile=A endpoint=GET /api/v1/chat/sessions key=old_key session=- result=200' $secondStartAt
    $secondKeyState = Assert-LegacyKey $appData $profileId $oldSecret
    if ($secondKeyState.Hash -ne $firstKeyState.Hash) { throw 'Legacy key metadata changed between the two candidate launches.' }
    $secondHistory = Get-History $historyPath
    Assert-SeedHistory $secondHistory $username $sessionA $sessionB
    Stop-App $candidateSecondApp
    $candidateSecondApp = $null
    Stop-RunnerChatboxProcesses
    Write-Evidence 'Candidate Start Menu launch 2 preserved the migrated DPAPI credential, metadata, legacy configurations, conversations, and image references.'

    $candidateUiUnsupported = $false
    $candidateUiFailure = $false
    $uiStartAt = (Get-ServiceLines).Count
    $candidateThirdApp = Start-InstalledApp $candidateExe $true $localAppData $fixtureTrust
    Wait-ServiceRequest 'profile=A endpoint=GET /api/v1/chat/sessions key=old_key session=- result=200' $uiStartAt
    if (-not (Test-CandidateUIA $candidateThirdApp)) {
        $candidateThirdApp.Refresh()
        if ($candidateThirdApp.HasExited) { throw 'Candidate app exited before interactive acceptance could begin.' }
        $candidateUiUnsupported = $true
        Write-Evidence 'Interactive candidate chat/file acceptance unsupported: this hosted UI did not expose the required controls through UI Automation; no app sends were counted.'
    }
    else {
        try {
            $uiStartAt = (Get-ServiceLines).Count
            [void] (Invoke-CandidateUI $candidateThirdApp (Join-Path $fixtureRunRoot 'media\upload-a.png') $uiStartAt)
            $candidateUiAccepted = $true
            Write-Evidence 'Candidate interactive chat/file acceptance passed with the installed Start Menu app.'
        }
        catch {
            if ($_.Exception.Message.StartsWith('UIA_UNAVAILABLE:')) {
                $candidateUiUnsupported = $true
                Write-Evidence 'Interactive candidate chat/file acceptance unsupported: the hosted UI did not expose a required automation control; no complete send result was counted.'
            }
            else {
                $candidateUiFailure = $true
                Write-Evidence 'Interactive candidate acceptance failed after UI control discovery; a real app request or response assertion did not pass.'
            }
        }
    }
    Stop-App $candidateThirdApp
    $candidateThirdApp = $null
    Stop-RunnerChatboxProcesses

    if ($candidateUiAccepted) {
        $null = Wait-ReplyImage $appData $profileId 'reply-image-a'
        $historyAfterUi = Get-History $historyPath
        Assert-SeedHistory $historyAfterUi $username $sessionA $sessionB
        $profileAAfterUi = @($historyAfterUi.profiles) | Where-Object id -eq $profileId | Select-Object -First 1
        $sessionAfterUi = @($profileAAfterUi.sessions) | Where-Object id -eq $sessionA | Select-Object -First 1
        foreach ($message in @('Hosted candidate chat acceptance.', 'Hosted candidate image acceptance.')) {
            if (@($sessionAfterUi.entries | Where-Object { $_.question -eq $message -and $_.status -eq '已完成' }).Count -ne 1) {
                throw 'Candidate UI messages were not persisted as completed history entries.'
            }
        }
        $imageEntry = $sessionAfterUi.entries | Where-Object question -eq 'Hosted candidate image acceptance.' | Select-Object -First 1
        if (-not $imageEntry.attachmentDisplayName -or @($imageEntry.replyImages).Count -lt 1) { throw 'Candidate image upload/reply metadata was not persisted.' }
    }

    $serviceLines = Get-ServiceLines
    $candidateLines = $serviceLines | Select-Object -Skip $linesBeforeRealUpgrade
    $postChat = @($candidateLines | Where-Object { $_ -match 'profile=A endpoint=POST /api/v1/chat key=old_key .* result=200' }).Count
    $postFile = @($candidateLines | Where-Object { $_ -match 'profile=A endpoint=POST /api/v1/file key=old_key .* result=200' }).Count
    Write-Evidence "Post-upgrade fixture response counts: POST chat=$postChat; POST file=$postFile."
    $uiStatus = if ($candidateUiAccepted) { 'PASS' } elseif ($candidateUiUnsupported) { 'UNSUPPORTED' } else { 'FAIL' }
    Write-Evidence "Interactive candidate chat/file status: $uiStatus."
    Write-Evidence 'Limit: provider calls are exercised against the isolated loopback fixture; no external AstrBot or model provider is contacted.'
    Write-Evidence "Safe CI evidence paths: $summaryPath; $(Join-Path $testRootPath 'real-fixture-service.log'); $legacyInstallLog; $candidateUpgradeLog."
    foreach ($line in $candidateLines) { Write-Host $line }
    Copy-Item -LiteralPath $safeServiceLog -Destination (Join-Path $testRootPath 'real-fixture-service.log') -Force
    if ($candidateUiFailure) { throw 'Interactive candidate chat/file acceptance failed after UIA controls were available; real upgrade and two-launch assertions passed.' }
}
finally {
    foreach ($process in @($candidateThirdApp, $candidateSecondApp, $candidateFirstApp, $oldApp)) { Stop-App $process }
    Stop-RunnerChatboxProcesses
    if ($null -ne $fixtureRunRoot) {
        $stopFixture = Join-Path $PSScriptRoot '..\fixture\Stop-Fixture.ps1'
        try { & $stopFixture -RunRoot $fixtureRunRoot | Out-Null } catch { Write-Host 'Fixture process will be left to hosted-runner cleanup.' }
        if ($safeServiceLog -and (Test-Path -LiteralPath $safeServiceLog)) {
            Copy-Item -LiteralPath $safeServiceLog -Destination (Join-Path $testRootPath 'real-fixture-service.log') -Force
        }
    }
    if ($null -ne $candidateDatabase) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($candidateDatabase) }
    if ($null -ne $legacyDatabase) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($legacyDatabase) }
    if ($null -ne $installer) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) }
    $env:LOCALAPPDATA = $oldLocalAppData
    $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions
}
