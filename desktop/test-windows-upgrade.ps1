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

function Invoke-Msi([string[]] $Arguments, [string] $LogPath) {
    & "$env:SystemRoot\System32\msiexec.exe" @Arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        if (Test-Path -LiteralPath $LogPath) { Get-Content -LiteralPath $LogPath -Tail 100 }
        throw "msiexec failed with exit code $exitCode. Log: $LogPath"
    }
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
    $candidateExecutables = @(Get-ChildItem -LiteralPath $candidateRoot -Recurse -File -Filter 'Chatbox.exe')
    if ($candidateJvms.Count -ne 1 -or $candidateJavas.Count -ne 1 -or $candidateExecutables.Count -ne 1) {
        throw "Expected one packaged jvm.dll, java.dll and Chatbox.exe; found $($candidateJvms.Count), $($candidateJavas.Count), $($candidateExecutables.Count)."
    }

    $installer = New-Object -ComObject WindowsInstaller.Installer
    if ((Get-RelatedProducts $installer $upgradeCode).Count -ne 0) {
        throw "A Chatbox product with UpgradeCode $upgradeCode is already installed on this runner."
    }
    $candidateDatabase = $installer.OpenDatabase($msiPath, 0)
    if ((Get-MsiProperty $candidateDatabase 'ProductVersion') -ne '0.1.4') {
        throw "Expected candidate ProductVersion 0.1.4, got $(Get-MsiProperty $candidateDatabase 'ProductVersion')."
    }
    $candidateProductCode = Get-MsiProperty $candidateDatabase 'ProductCode'
    if ((Get-MsiProperty $candidateDatabase 'UpgradeCode') -ne $upgradeCode) {
        throw "Candidate MSI UpgradeCode does not match $upgradeCode."
    }
    if ((Get-JvmComponentId $candidateDatabase) -ne $jvmComponentCode) {
        throw "Candidate jvm.dll does not use the expected component ID $jvmComponentCode."
    }

    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    $syntheticJvmPath = Join-Path $testRoot 'jvm.dll'
    $sourcePath = Join-Path $testRoot 'synthetic-jvm.c'
    $resourceSourcePath = Join-Path $testRoot 'jvm-version.rc'
    $resourcePath = Join-Path $testRoot 'jvm-version.res'
    $objectPath = Join-Path $testRoot 'synthetic-jvm.obj'
    $buildScriptPath = Join-Path $testRoot 'build-synthetic-jvm.cmd'
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere)) { throw 'Visual Studio discovery tool was not found on the runner.' }
    $vsInstall = & $vswhere -latest -products '*' -property installationPath
    $vsDevCmd = Join-Path $vsInstall 'Common7\Tools\VsDevCmd.bat'
    if (-not (Test-Path -LiteralPath $vsDevCmd)) { throw 'Visual Studio native build tools were not found on the runner.' }
    Set-Content -LiteralPath $sourcePath -Encoding ASCII -Value @'
#include <windows.h>
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) { return TRUE; }
'@
    Set-Content -LiteralPath $resourceSourcePath -Encoding ASCII -Value @'
#include <winver.h>
VS_VERSION_INFO VERSIONINFO
 FILEVERSION 99,0,0,0
 PRODUCTVERSION 99,0,0,0
 FILEFLAGSMASK 0x3fL
 FILEFLAGS 0x0L
 FILEOS 0x40004L
 FILETYPE 0x2L
 FILESUBTYPE 0x0L
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "040904B0"
    BEGIN
      VALUE "CompanyName", "Chatbox CI\0"
      VALUE "FileDescription", "Chatbox upgrade regression fixture\0"
      VALUE "FileVersion", "99.0.0.0\0"
      VALUE "InternalName", "jvm.dll\0"
      VALUE "OriginalFilename", "jvm.dll\0"
      VALUE "ProductName", "Chatbox\0"
      VALUE "ProductVersion", "99.0.0.0\0"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x409, 1200
  END
END
'@
    $buildScript = @"
@echo off
call "$vsDevCmd" -no_logo -arch=x64
if errorlevel 1 exit /b %errorlevel%
rc.exe /nologo /fo "$resourcePath" "$resourceSourcePath"
if errorlevel 1 exit /b %errorlevel%
cl.exe /nologo /c /O1 /Fo"$objectPath" "$sourcePath"
if errorlevel 1 exit /b %errorlevel%
link.exe /nologo /DLL /MACHINE:X64 /OUT:"$syntheticJvmPath" "$objectPath" "$resourcePath"
exit /b %errorlevel%
"@
    Set-Content -LiteralPath $buildScriptPath -Value $buildScript -Encoding ASCII
    $build = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', "call `"$buildScriptPath`"") -Wait -PassThru -NoNewWindow
    if ($build.ExitCode -ne 0) { throw "Native fixture compilation failed with exit code $($build.ExitCode)." }
    $oldVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($syntheticJvmPath).FileVersion
    if ([Version]$oldVersion -lt [Version]'99.0.0.0') {
        throw "Synthetic jvm.dll did not get the required high PE file version: $oldVersion"
    }

    $escapedJvmPath = [Security.SecurityElement]::Escape($syntheticJvmPath)
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
    Invoke-Msi @('/i', $legacyMsiPath, '/qn', "INSTALLDIR=$installDir", '/L*v', $legacyLog) $legacyLog
    $installedJvm = Join-Path $installDir 'runtime\bin\server\jvm.dll'
    $installedVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($installedJvm).FileVersion
    if ([Version]$installedVersion -lt [Version]'99.0.0.0') {
        throw "The installed legacy jvm.dll lost its high PE file version: $installedVersion"
    }
    $sentinel = Join-Path $installDir 'shared-directory-sentinel.txt'
    Set-Content -LiteralPath $sentinel -Value 'preserve across upgrade' -NoNewline

    # This is deliberately a normal major-upgrade install: do not pass REINSTALLMODE.
    Invoke-Msi @('/i', $msiPath, '/qn', "INSTALLDIR=$installDir", '/L*v', $upgradeLog) $upgradeLog

    $relatedProducts = Get-RelatedProducts $installer $upgradeCode
    if ($relatedProducts -contains $legacyProductCode) { throw 'The old 0.1.2 MSI product remains installed after upgrade.' }
    if ($relatedProducts -notcontains $candidateProductCode) { throw 'The candidate MSI is not registered after upgrade.' }
    if (-not (Test-Path -LiteralPath $sentinel) -or (Get-Content -Raw -LiteralPath $sentinel) -ne 'preserve across upgrade') {
        throw 'The shared-directory sentinel was not retained by the upgrade.'
    }

    foreach ($name in @('jvm.dll', 'java.dll')) {
        $candidateFiles = if ($name -eq 'jvm.dll') { $candidateJvms } else { $candidateJavas }
        $installedFile = Join-Path $installDir $(if ($name -eq 'jvm.dll') { 'runtime\bin\server\jvm.dll' } else { 'runtime\bin\java.dll' })
        if (-not (Test-Path -LiteralPath $installedFile)) { throw "Upgraded package is missing $installedFile" }
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
    Write-Host "MSI upgrade regression passed. Legacy file version $oldVersion was replaced; logs: $legacyLog, $upgradeLog"
}
finally {
    if ($null -ne $candidateDatabase) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($candidateDatabase) }
    if ($null -ne $installer) { [void] [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) }
}
