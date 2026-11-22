[CmdletBinding()]
param(
    [string]$ScratchRoot = 'D:\Chatbox验收 V1\ui-redesign'
)

$ErrorActionPreference = 'Stop'
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $ScratchRoot 'chatbox-fixture'))
New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null

$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$previousErrorPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & icacls.exe $fixtureRoot '/inheritance:r' '/grant:r' ('*' + $currentSid.Value + ':(OI)(CI)F') 2>&1 | Out-Null
    $aclExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorPreference
}
if ($aclExitCode -ne 0) { throw 'Could not restrict the fixture directory to the current user.' }

$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$runRoot = Join-Path $fixtureRoot ('run-' + $runId)
$privateDir = Join-Path $runRoot 'private'
$classesDir = Join-Path $runRoot 'classes'
$tlsDir = Join-Path $runRoot 'tls'
$localAppData = Join-Path $runRoot 'localappdata'
$appData = Join-Path $localAppData 'Chatbox'
$credentialsDir = Join-Path $appData 'credentials'
$mediaDir = Join-Path $runRoot 'media'
New-Item -ItemType Directory -Force -Path $privateDir, $classesDir, $tlsDir, $credentialsDir, $mediaDir | Out-Null

function New-RandomToken {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-Sha256([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return $sha.ComputeHash($Bytes) } finally { $sha.Dispose() }
}

function ConvertTo-PrivatePath([string]$Value) {
    return $Value.Replace('\', '/')
}

$username = 'ui_fixture_' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$sessionA = [Guid]::NewGuid().ToString()
$sessionB = [Guid]::NewGuid().ToString()
$baseA = 'https://127.0.0.1:18443'
$baseB = 'https://127.0.0.1:18444'
$oldKey = New-RandomToken
$secondKeyA = New-RandomToken
$secondKeyB = New-RandomToken
$invalidKey = New-RandomToken
$tlsPassword = New-RandomToken
$trustPassword = 'fixturetrust'
$serverStore = Join-Path $tlsDir 'fixture-server.p12'
$trustStore = Join-Path $tlsDir 'fixture-app-trust.p12'
$certificate = Join-Path $tlsDir 'fixture-server.cer'
$privateProps = Join-Path $privateDir 'fixture-private.properties'
$controlsFile = Join-Path $privateDir 'controls.properties'
$serviceLog = Join-Path $privateDir 'service.log'
$serviceErrorLog = Join-Path $privateDir 'service-error.log'
$pidFile = Join-Path $privateDir 'service.pid'
$readyFile = Join-Path $privateDir 'service.ready'

$java = (Get-Command java.exe -ErrorAction Stop).Source
$javac = (Get-Command javac.exe -ErrorAction Stop).Source
$keytool = (Get-Command keytool.exe -ErrorAction Stop).Source

Add-Type -AssemblyName System.Security
$profileIdA = 'manual:' + $baseA
$profileIdB = 'manual:' + $baseB
$profileHashA = Get-Sha256 ([Text.Encoding]::UTF8.GetBytes($profileIdA))
$credentialName = 'credential-' + [Convert]::ToBase64String($profileHashA).TrimEnd('=').Replace('+', '-').Replace('/', '_') + '.bin'
$tokenBytes = [Text.Encoding]::UTF8.GetBytes($oldKey)
$encryptedToken = [Security.Cryptography.ProtectedData]::Protect($tokenBytes, $profileHashA, [Security.Cryptography.DataProtectionScope]::CurrentUser)
[IO.File]::WriteAllBytes((Join-Path $credentialsDir $credentialName), $encryptedToken)
$answerA1 = '把画面主体留在左侧，右侧用一行标题和短说明，层级会更清楚。'
$answerB1 = '这款轻巧的桌面音箱让清晰人声和简洁外观同时融入日常空间。'
$history = [ordered]@{
    schemaVersion = 2
    username = $username
    activeProfileId = $profileIdA
    profiles = @(
        @{
            id = $profileIdA
            address = $baseA
            kind = 'manual'
            offlineOnly = $false
            currentSession = $sessionA
            sessions = @(
                @{
                    id = $sessionA
                    entries = @(
                        @{
                            question = '这张图里的色块适合做主视觉吗？'
                            answer = $answerA1
                            status = '已完成'
                            replyImages = @(@{ attachmentId = 'reply-image-a'; textOffset = $answerA1.Length })
                        },
                        @{
                            question = '能把建议压缩成一句话吗？'
                            answer = '用清晰留白衬托主体，再以短标题传达重点。'
                            status = '已完成'
                            replyImages = @()
                        }
                    )
                }
            )
        },
        @{
            id = $profileIdB
            address = $baseB
            kind = 'manual'
            offlineOnly = $false
            currentSession = $sessionB
            sessions = @(
                @{
                    id = $sessionB
                    entries = @(
                        @{
                            question = '帮我把这份产品说明整理成一句话。'
                            answer = $answerB1
                            status = '已完成'
                            replyImages = @(@{ attachmentId = 'reply-image-b'; textOffset = $answerB1.Length })
                        },
                        @{
                            question = '接下来优先准备什么？'
                            answer = '先补齐场景照片，再检查包装尺寸与桌面摆放效果。'
                            status = '已完成'
                            replyImages = @()
                        }
                    )
                }
            )
        }
    )
} | ConvertTo-Json -Depth 16 -Compress
[IO.File]::WriteAllText((Join-Path $appData 'history.json'), $history, (New-Object Text.UTF8Encoding($false)))
[IO.File]::WriteAllText($controlsFile, 'reject.original=false' + [Environment]::NewLine, [Text.Encoding]::ASCII)

$blobCheck = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $credentialsDir $credentialName)), $profileHashA, [Security.Cryptography.DataProtectionScope]::CurrentUser)
if ([Text.Encoding]::UTF8.GetString($blobCheck) -cne $oldKey) { throw 'Synthetic DPAPI fixture verification failed.' }
$tokenBytes.Clear()
$blobCheck = $null
$encryptedToken = $null

$previousErrorPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $keytool -genkeypair -alias fixture-server -keyalg RSA -keysize 2048 -validity 2 -storetype PKCS12 -keystore $serverStore -storepass $tlsPassword -keypass $tlsPassword -dname 'CN=127.0.0.1' -ext 'SAN=IP:127.0.0.1,DNS:localhost' -noprompt 2>&1 | Out-Null
    $generateExitCode = $LASTEXITCODE
    if ($generateExitCode -eq 0) {
        & $keytool -exportcert -alias fixture-server -keystore $serverStore -storepass $tlsPassword -file $certificate 2>&1 | Out-Null
        $exportExitCode = $LASTEXITCODE
    }
    if ($generateExitCode -eq 0 -and $exportExitCode -eq 0) {
        & $keytool -importcert -noprompt -alias fixture-server -file $certificate -keystore $trustStore -storetype PKCS12 -storepass $trustPassword 2>&1 | Out-Null
        $importExitCode = $LASTEXITCODE
    }
} finally {
    $ErrorActionPreference = $previousErrorPreference
}
if ($generateExitCode -ne 0) { throw 'Could not generate the ephemeral HTTPS certificate.' }
if ($exportExitCode -ne 0) { throw 'Could not export the ephemeral HTTPS certificate.' }
if ($importExitCode -ne 0) { throw 'Could not create the app-specific truststore.' }

$props = [ordered]@{
    'base.a' = $baseA
    'base.b' = $baseB
    username = $username
    'session.a' = $sessionA
    'session.b' = $sessionB
    'token.a.original' = $oldKey
    'token.a.second' = $secondKeyA
    'token.b.second' = $secondKeyB
    'token.invalid' = $invalidKey
    'tls.password' = $tlsPassword
    'tls.trustPassword' = $trustPassword
    'tls.serverStore' = ConvertTo-PrivatePath $serverStore
    'tls.trustStore' = ConvertTo-PrivatePath $trustStore
    'controls.file' = ConvertTo-PrivatePath $controlsFile
    'media.dir' = ConvertTo-PrivatePath $mediaDir
    'ready.file' = ConvertTo-PrivatePath $readyFile
}
$propertiesText = ($props.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) -join [Environment]::NewLine
[IO.File]::WriteAllText($privateProps, $propertiesText + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))

& $javac --add-modules jdk.httpserver -d $classesDir (Join-Path $PSScriptRoot 'FixtureServer.java') (Join-Path $PSScriptRoot 'FixtureProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the local fixture service and probe.' }

$argumentLine = '--add-modules=jdk.httpserver -cp "' + $classesDir + '" FixtureServer "' + $privateProps + '"'
$service = Start-Process -FilePath $java -ArgumentList $argumentLine -WorkingDirectory $runRoot -WindowStyle Hidden -RedirectStandardOutput $serviceLog -RedirectStandardError $serviceErrorLog -PassThru
[IO.File]::WriteAllText($pidFile, [string]$service.Id, [Text.Encoding]::ASCII)
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    if ($service.HasExited) { break }
    if (Test-Path -LiteralPath $readyFile) { $ready = $true; break }
    Start-Sleep -Milliseconds 250
}
if (-not $ready) {
    if (-not $service.HasExited) { Stop-Process -Id $service.Id -Force }
    throw 'The local HTTPS fixture did not become ready. Check the safe service logs in the run directory.'
}

$probe = & $java --add-modules=jdk.httpserver -cp $classesDir FixtureProbe $privateProps 2>&1
if ($LASTEXITCODE -ne 0) {
    Stop-Process -Id $service.Id -Force
    throw 'The local HTTPS protocol probe failed. Check safe service logs in the run directory.'
}
Write-Output 'FIXTURE READY'
Write-Output ('Run root: ' + $runRoot)
Write-Output ('Profile A: ' + $baseA)
Write-Output ('Profile B: ' + $baseB)
Write-Output ('Legacy history: ' + (Join-Path $appData 'history.json'))
Write-Output ('DPAPI and key metadata: ' + $credentialsDir)
Write-Output ('Private harness configuration (contains synthetic secrets): ' + $privateProps)
Write-Output ('App truststore: ' + $trustStore)
Write-Output ('Synthetic upload images: ' + $mediaDir)
Write-Output ('Safe service log: ' + $serviceLog)
Write-Output ('Service PID: ' + $service.Id)
Write-Output 'Credentials are available only in the ACL-protected private harness configuration.'
Write-Output 'Probe:'
$probe | ForEach-Object { Write-Output $_ }
