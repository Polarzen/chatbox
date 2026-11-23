[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $RunRoot,
    [Parameter(Mandatory)] [string] $Executable
)

$ErrorActionPreference = 'Stop'
$run = (Resolve-Path -LiteralPath $RunRoot).Path
$exe = (Resolve-Path -LiteralPath $Executable).Path
$trust = Join-Path $run 'tls\fixture-app-trust.p12'
$local = Join-Path $run 'localappdata'
foreach ($required in @($trust, "$local\Chatbox\history.json", "$run\private\service.ready")) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing fixture file $required" }
}
$shell = New-Object -ComObject WScript.Shell
$menus = @([Environment]::GetFolderPath('CommonPrograms'), [Environment]::GetFolderPath('Programs'))
$shortcut = @(Get-ChildItem -LiteralPath $menus -Filter 'Chatbox.lnk' -Recurse -File | Where-Object {
    $shell.CreateShortcut($_.FullName).TargetPath -eq $exe
})
if ($shortcut.Count -ne 1) { throw 'Expected exactly one installed Start menu shortcut for this executable.' }
$previousLocal = $env:LOCALAPPDATA
$previousJvm = $env:JAVA_TOOL_OPTIONS
try {
    $env:LOCALAPPDATA = $local
    $env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStore="' + $trust +
        '" -Djavax.net.ssl.trustStorePassword=fixturetrust -Djavax.net.ssl.trustStoreType=PKCS12'
    $launcher = Start-Process -FilePath $shortcut[0].FullName -PassThru
} finally {
    $env:LOCALAPPDATA = $previousLocal
    $env:JAVA_TOOL_OPTIONS = $previousJvm
}
$deadline = [DateTime]::UtcNow.AddSeconds(45)
while ([DateTime]::UtcNow -lt $deadline) {
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($launcher.Id) AND Name='Chatbox.exe'"
    foreach ($id in @($launcher.Id) + @($children.ProcessId)) {
        if (-not $id) { continue }
        $window = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($window -and $window.Path -eq $exe -and $window.MainWindowHandle -ne 0) {
            [PSCustomObject]@{ Shortcut = $shortcut[0].FullName; LauncherPid = $launcher.Id
                WindowPid = $id; WindowHandle = $window.MainWindowHandle.ToInt64() }
            return
        }
    }
    Start-Sleep -Milliseconds 200
}
throw 'The isolated shortcut launch did not expose its own installed Chatbox window.'
