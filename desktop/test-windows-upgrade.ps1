param(
    [Parameter(Mandatory = $true)] [string] $MsiPath,
    [Parameter(Mandatory = $true)] [string] $CandidateRoot
)

$ErrorActionPreference = 'Stop'

if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:OS -ne 'Windows_NT') {
    throw 'This MSI upgrade test is restricted to a GitHub-hosted Windows Actions runner.'
}

$runnerTemp = [IO.Path]::GetFullPath($env:RUNNER_TEMP).TrimEnd('\')
$testRoot = Join-Path $runnerTemp ('chatbox-upgrade-regression-' + [Guid]::NewGuid().ToString('N'))
$installDir = Join-Path $testRoot 'install'
$wxsPath = Join-Path $testRoot 'legacy.wxs'
$wixobjPath = Join-Path $testRoot 'legacy.wixobj'
$legacyMsiPath = Join-Path $testRoot 'legacy.msi'
$legacyLog = Join-Path $testRoot 'legacy-install.log'
$upgradeLog = Join-Path $testRoot 'candidate-upgrade.log'
$legacyProductCode = '{' + [Guid]::NewGuid().ToString().ToUpperInvariant() + '}'
$upgradeCode = '{204B3F78-64EC-4071-9A66-A535773540EB}'
$jvmComponentCode = '{0566E653-E9AE-32AD-8448-1AC3F202A898}'
$installer = $null
$candidateDatabase = $null
$candidateProductCode = $null

function Get-FileVersion([string] $Path) {
    $info = [Diagnostics.FileVersionInfo]::GetVersionInfo($Path)
    return [Version]::new($info.FileMajorPart, $info.FileMinorPart, $info.FileBuildPart, $info.FilePrivatePart)
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

function Get-JvmComponentId($Database) {
    $view = $null
    $componentIds = @()
    try {
        $view = $Database.OpenView('SELECT `File`.`FileName`, `Component`.`ComponentId` FROM `File`, `Component` WHERE `File`.`Component_` = `Component`.`Component`')
        [void] $view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            $fileName = ($record.StringData(1) -split '\|')[-1]
            if ($fileName -ieq 'jvm.dll') { $componentIds += $record.StringData(2) }
        }
    }
    finally {
        if ($null -ne $view) { [void] $view.Close(); [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) }
    }
    if ($componentIds.Count -ne 1) { throw "Expected exactly one jvm.dll component in the candidate MSI, found $($componentIds.Count)." }
    return $componentIds[0]
}

function Get-RelatedProducts($InstallerObject, [string] $Code) {
    return @($InstallerObject.RelatedProducts($Code))
}

function Invoke-Msi([string] $MsiPath, [string] $InstallDir, [string] $LogPath) {
    $arguments = "/i `"$MsiPath`" /qn INSTALLDIR=`"$InstallDir`" /L*v `"$LogPath`""
    Write-Host "msiexec.exe $arguments"
    $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $arguments -Wait -PassThru -NoNewWindow
    $exitCode = $process.ExitCode
    if ($exitCode -ne 0) {
        if (Test-Path -LiteralPath $LogPath) { Get-Content -LiteralPath $LogPath -Tail 100 }
        throw "msiexec failed with exit code $exitCode. Log: $LogPath"
    }
    return $exitCode
}

function Write-MsiDiagnostics([string] $Step, [string] $InstallDir, [string] $ExpectedFile, [int] $ExitCode, [string] $LogPath) {
    Write-Host "MSI $Step exit code: $ExitCode; expected: $ExpectedFile; INSTALLDIR: $InstallDir"
    if (Test-Path -LiteralPath $InstallDir) {
        Get-ChildItem -LiteralPath $InstallDir -Recurse -Force -ErrorAction Continue | ForEach-Object { Write-Host $_.FullName }
    }
    else { Write-Host "INSTALLDIR does not exist: $InstallDir" }
    if (Test-Path -LiteralPath $LogPath) {
        Write-Host "Relevant MSI log lines from $LogPath"
        Select-String -LiteralPath $LogPath -Pattern 'Command Line|Property\(S\): INSTALLDIR|INSTALLDIR =|FileCopy|jvm\.dll|Return value 3' |
            Select-Object -Last 120 | ForEach-Object { Write-Host $_.Line }
    }
    else { Write-Host "MSI log was not created: $LogPath" }
}

try {
    $msiPath = (Resolve-Path -LiteralPath $MsiPath).Path
    $candidateRoot = (Resolve-Path -LiteralPath $CandidateRoot).Path
    $runnerPrefix = $runnerTemp + '\'
    if (-not $testRoot.StartsWith($runnerPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Regression files must stay under RUNNER_TEMP: $testRoot"
    }

    $candidateJvms = @(Get-ChildItem -LiteralPath $candidateRoot -Recurse -File -Filter 'jvm.dll')
    $candidateJavas = @(Get-ChildItem -LiteralPath $candidateRoot -Recurse -File -Filter 'java.dll')
    if ($candidateJvms.Count -ne 1 -or $candidateJavas.Count -ne 1) {
        throw "Expected one packaged jvm.dll and java.dll; found $($candidateJvms.Count), $($candidateJavas.Count)."
    }
    $candidateJvmVersion = Get-FileVersion $candidateJvms[0].FullName

    $installer = New-Object -ComObject WindowsInstaller.Installer
    if ((Get-RelatedProducts $installer $upgradeCode).Count -ne 0) {
        throw "A Chatbox product with UpgradeCode $upgradeCode is already installed on this runner."
    }
    $candidateDatabase = $installer.OpenDatabase($msiPath, 0)
    $candidateVersion = [Version](Get-MsiProperty $candidateDatabase 'ProductVersion')
    $legacyVersion = [Version]'0.1.2'
    if ($candidateVersion -le $legacyVersion) {
        throw "Candidate ProductVersion $candidateVersion must be newer than fixture $legacyVersion."
    }
    $candidateProductCode = Get-MsiProperty $candidateDatabase 'ProductCode'
    if ((Get-MsiProperty $candidateDatabase 'UpgradeCode') -ne $upgradeCode) {
        throw "Candidate MSI UpgradeCode does not match $upgradeCode."
    }
    if ((Get-JvmComponentId $candidateDatabase) -ne $jvmComponentCode) {
        throw "Candidate jvm.dll does not use the expected component ID $jvmComponentCode."
    }
    [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($candidateDatabase)
    $candidateDatabase = $null

    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    $jdk21Home = $env:JAVA_HOME_21_X64
    if ([string]::IsNullOrWhiteSpace($jdk21Home)) { throw 'GitHub hosted runner did not provide JAVA_HOME_21_X64 for the old high-version runtime fixture.' }
    $oldJvmPath = Join-Path $jdk21Home 'bin\server\jvm.dll'
    if (-not (Test-Path -LiteralPath $oldJvmPath)) { throw "JDK 21 fixture jvm.dll was not found: $oldJvmPath" }
    $oldJvmVersion = Get-FileVersion $oldJvmPath
    if ($oldJvmVersion -le $candidateJvmVersion) {
        throw "Runner JDK 21 jvm.dll version $oldJvmVersion is not newer than candidate JDK version $candidateJvmVersion."
    }

    Write-Host "MSI logs: $legacyLog and $upgradeLog"
    $escapedJvmPath = [Security.SecurityElement]::Escape($oldJvmPath)
    $wxs = @"
<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="$legacyProductCode" Name="Chatbox 0.1.2 regression fixture" Language="1033" Version="0.1.2" Manufacturer="Chatbox CI" UpgradeCode="$upgradeCode">
    <Package InstallerVersion="500" Compressed="yes" InstallScope="perMachine" />
    <MajorUpgrade DowngradeErrorMessage="A newer Chatbox is already installed." />
    <MediaTemplate EmbedCab="yes" />
    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="ProgramFiles64Folder">
        <Directory Id="INSTALLDIR" Name="Chatbox">
          <Directory Id="RuntimeFolder" Name="runtime">
            <Directory Id="BinFolder" Name="bin">
              <Directory Id="ServerFolder" Name="server" />
            </Directory>
          </Directory>
        </Directory>
      </Directory>
    </Directory>
    <Feature Id="MainFeature" Title="Chatbox" Level="1">
      <ComponentRef Id="JvmComponent" />
    </Feature>
    <DirectoryRef Id="ServerFolder">
      <Component Id="JvmComponent" Guid="$jvmComponentCode" Win64="yes">
        <File Id="JvmFile" Name="jvm.dll" Source="$escapedJvmPath" KeyPath="yes" />
      </Component>
    </DirectoryRef>
  </Product>
</Wix>
"@
    Set-Content -LiteralPath $wxsPath -Value $wxs -Encoding UTF8
    & candle.exe -nologo -arch x64 -out $wixobjPath $wxsPath
    if ($LASTEXITCODE -ne 0) { throw "WiX candle failed: $LASTEXITCODE" }
    & light.exe -nologo -out $legacyMsiPath $wixobjPath
    if ($LASTEXITCODE -ne 0) { throw "WiX light failed: $LASTEXITCODE" }

    New-Item -ItemType Directory -Path $installDir -Force | Out-Null
    $legacyExitCode = Invoke-Msi $legacyMsiPath $installDir $legacyLog
    $installedJvm = Join-Path $installDir 'runtime\bin\server\jvm.dll'
    if (-not (Test-Path -LiteralPath $installedJvm)) {
        Write-MsiDiagnostics 'legacy fixture install' $installDir $installedJvm $legacyExitCode $legacyLog
        throw "Legacy fixture install returned $legacyExitCode but did not create $installedJvm. Log: $legacyLog"
    }
    $installedVersion = Get-FileVersion $installedJvm
    if ($installedVersion -ne $oldJvmVersion) {
        throw "The installed legacy jvm.dll version $installedVersion does not match fixture version $oldJvmVersion."
    }
    $sentinel = Join-Path $installDir 'shared-directory-sentinel.txt'
    Set-Content -LiteralPath $sentinel -Value 'preserve across upgrade' -NoNewline

    # This is deliberately a normal major-upgrade install: do not pass REINSTALLMODE.
    $candidateExitCode = Invoke-Msi $msiPath $installDir $upgradeLog

    $relatedProducts = Get-RelatedProducts $installer $upgradeCode
    if ($relatedProducts -contains $legacyProductCode) { throw 'The old 0.1.2 MSI product remains installed after upgrade.' }
    if ($relatedProducts -notcontains $candidateProductCode) { throw 'The candidate MSI is not registered after upgrade.' }
    if (-not (Test-Path -LiteralPath $sentinel) -or (Get-Content -Raw -LiteralPath $sentinel) -ne 'preserve across upgrade') {
        throw 'The shared-directory sentinel was not retained by the upgrade.'
    }

    foreach ($name in @('jvm.dll', 'java.dll')) {
        $candidateFiles = if ($name -eq 'jvm.dll') { $candidateJvms } else { $candidateJavas }
        $installedFile = Join-Path $installDir $(if ($name -eq 'jvm.dll') { 'runtime\bin\server\jvm.dll' } else { 'runtime\bin\java.dll' })
        if (-not (Test-Path -LiteralPath $installedFile)) {
            Write-MsiDiagnostics 'candidate upgrade' $installDir $installedFile $candidateExitCode $upgradeLog
            throw "Upgraded package is missing $installedFile. Log: $upgradeLog"
        }
        $candidateHash = (Get-FileHash -LiteralPath $candidateFiles[0].FullName -Algorithm SHA256).Hash
        $installedHash = (Get-FileHash -LiteralPath $installedFile -Algorithm SHA256).Hash
        if ($candidateHash -ne $installedHash) {
            throw "Upgraded $name does not byte-match the candidate MSI payload."
        }
    }

    $installedExe = Join-Path $installDir 'Chatbox.exe'
    if (-not (Test-Path -LiteralPath $installedExe)) { throw "Upgraded package is missing $installedExe" }
    $smokeMarker = Join-Path $testRoot 'installed-smoke-marker.txt'
    $process = Start-Process -FilePath $installedExe -ArgumentList @('--smoke-test', "`"$smokeMarker`"") -PassThru
    if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Installed app did not finish its smoke test within 30 seconds.' }
    if ($process.ExitCode -ne 0) { throw "Installed app smoke test failed with exit code $($process.ExitCode)." }
    if (-not (Test-Path -LiteralPath $smokeMarker) -or (Get-Content -Raw -LiteralPath $smokeMarker) -ne 'chatbox-smoke-ok') {
        throw 'Installed app smoke-test marker was not written.'
    }
    Write-Host "MSI upgrade regression passed. JDK 21 file version $oldJvmVersion was replaced by JDK 17 version $candidateJvmVersion."
}
finally {
    if ($null -ne $candidateDatabase) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($candidateDatabase) }
    if ($null -ne $installer) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) }
}
