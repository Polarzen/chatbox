param(
    [Parameter(Mandatory = $true)]
    [string]$MsiPath,

    [string]$ExpectedVersion = '0.1.24'
)

$ErrorActionPreference = 'Stop'

$MsiPath = (Resolve-Path -LiteralPath $MsiPath).Path
if ([IO.Path]::GetExtension($MsiPath) -ne '.msi') {
    throw "Expected an MSI path, got: $MsiPath"
}

$expectedUpgradeCode = '{204B3F78-64EC-4071-9A66-A535773540EB}'
$legacyKey = 'Software\Unknown\Chatbox\0.1.1'
$legacyValue = 'RM_RF48431AECBD69377EA800D62616352F20'
$guardProperty = 'CHATBOX_011_RMFE_PRESENT'
$guardSignature = 'CHATBOX_011_RMFE_SEARCH'
$overrideProperty = 'CHATBOX_SAFE_011_UPGRADE'
$guardCondition = $guardProperty + ' = "" OR ' + $overrideProperty + ' = "1"'
$guardMessage = 'Chatbox 0.1.1 has a legacy recursive cleanup setting. This installer is blocked unless the approved safe-upgrade procedure has redirected that setting to an empty, non-reparse-point directory. After verifying the redirect, rerun with CHATBOX_SAFE_011_UPGRADE=1.'

function ConvertTo-MsiSqlLiteral([string]$Value) {
    if ($Value.Contains("'")) {
        throw 'Windows Installer SQL does not support apostrophes in string literals.'
    }
    return "'$Value'"
}

function Invoke-MsiSql($Database, [string]$Query) {
    $view = $null
    try {
        $view = $Database.OpenView($Query)
        [void]$view.Execute()
    }
    finally {
        if ($null -ne $view) {
            [void]$view.Close()
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
        }
    }
}

function Get-MsiRows($Database, [string]$Table, [string[]]$Columns) {
    $quotedColumns = ($Columns | ForEach-Object { '`' + $_ + '`' }) -join ', '
    $query = 'SELECT ' + $quotedColumns + ' FROM `' + $Table + '`'
    $view = $null
    $rows = [System.Collections.Generic.List[object]]::new()
    try {
        $view = $Database.OpenView($query)
        [void]$view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            $row = [ordered]@{}
            for ($index = 1; $index -le $Columns.Count; $index++) {
                $value = $record.StringData($index)
                if ([string]::IsNullOrEmpty($value)) {
                    $integerValue = $record.IntegerData($index)
                    if ($integerValue -eq -2147483648) {
                        $value = $null
                    }
                    else {
                        $value = [int]$integerValue
                    }
                }
                $row[$Columns[$index - 1]] = $value
            }
            $rows.Add([pscustomobject]$row)
        }
    }
    finally {
        if ($null -ne $view) {
            [void]$view.Close()
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
        }
    }
    return $rows.ToArray()
}

function Test-MsiTable($Database, [string]$Table) {
    $tableNames = @(Get-MsiRows $Database '_Tables' @('Name'))
    return (@($tableNames | Where-Object { $_.Name -eq $Table }).Count -gt 0)
}

function Get-MsiProperty($Database, [string]$Name) {
    $properties = @(Get-MsiRows $Database 'Property' @('Property', 'Value'))
    $matches = @($properties | Where-Object { $_.Property -eq $Name })
    if ($matches.Count -ne 1) {
        throw "Expected one MSI property '$Name', found $($matches.Count)."
    }
    return [string]$matches[0].Value
}

function Set-MsiProperty($Database, [string]$Name, [string]$Value) {
    $properties = @(Get-MsiRows $Database 'Property' @('Property', 'Value'))
    $matches = @($properties | Where-Object { $_.Property -eq $Name })
    $quotedName = ConvertTo-MsiSqlLiteral $Name
    $quotedValue = ConvertTo-MsiSqlLiteral $Value
    if ($matches.Count -eq 0) {
        Invoke-MsiSql $Database "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ($quotedName, $quotedValue)"
    }
    elseif ($matches.Count -eq 1) {
        Invoke-MsiSql $Database "UPDATE ``Property`` SET ``Value``=$quotedValue WHERE ``Property``=$quotedName"
    }
    else {
        throw "MSI property '$Name' has duplicate rows."
    }
}

function Set-MsiSequence($Database, [string]$Table, [string]$Action, [int]$Sequence) {
    $rows = @(Get-MsiRows $Database $Table @('Action', 'Condition', 'Sequence'))
    $matching = @($rows | Where-Object { $_.Action -eq $Action })
    if ($matching.Count -gt 1) {
        throw "MSI action '$Action' has duplicate rows in '$Table'."
    }
    $occupied = @($rows | Where-Object { $_.Sequence -eq $Sequence -and $_.Action -ne $Action })
    if ($occupied.Count -gt 0) {
        throw "MSI sequence $Sequence in '$Table' is already occupied by '$($occupied[0].Action)'."
    }
    if ($matching.Count -eq 0) {
        $quotedAction = ConvertTo-MsiSqlLiteral $Action
        Invoke-MsiSql $Database "INSERT INTO ``$Table`` (``Action``, ``Condition``, ``Sequence``) VALUES ($quotedAction, NULL, $Sequence)"
    }
    else {
        $quotedAction = ConvertTo-MsiSqlLiteral $Action
        Invoke-MsiSql $Database "UPDATE ``$Table`` SET ``Sequence``=$Sequence WHERE ``Action``=$quotedAction"
    }
}

function Add-MsiRowIfMissing($Database, [string]$Table, $Fields, [string[]]$Identity) {
    $columns = @($Fields.Keys)
    $id = $Fields[$columns[0]]
    $exists = $false
    foreach ($row in @(Get-MsiRows $Database $Table $columns)) {
        $sameIdentity = $true
        foreach ($column in $Identity) {
            if ($row.$column -ne $Fields[$column]) { $sameIdentity = $false; break }
        }
        if ($row.($columns[0]) -ne $id -and -not $sameIdentity) { continue }
        foreach ($column in $columns) {
            if ($row.$column -cne $Fields[$column]) { throw "Conflicting $Table row for '$id'." }
        }
        $exists = $true
    }
    if ($exists) { return }
    $names = ($columns | ForEach-Object { '`' + $_ + '`' }) -join ', '
    $values = ($columns | ForEach-Object {
        $value = $Fields[$_]
        if ($null -eq $value) { 'NULL' }
        elseif ($value -is [int]) { [string]$value }
        else { ConvertTo-MsiSqlLiteral $value }
    }) -join ', '
    Invoke-MsiSql $Database "INSERT INTO ``$Table`` ($names) VALUES ($values)"
}

function Add-ReplyToastRegistration($Database) {
    if ((Get-MsiProperty $Database 'ALLUSERS') -ne '1') { throw 'Expected per-machine Chatbox installation.' }
    $launchers = @(Get-MsiRows $Database 'File' @('File','Component_','FileName') | Where-Object {
        ($_.FileName -split '\|')[-1] -eq 'Chatbox.exe'
    })
    if ($launchers.Count -ne 1) { throw 'Expected one tracked Chatbox.exe.' }
    $launcher = $launchers[0]
    $target = '[#' + $launcher.File + ']'
    $menuDirs = @('ProgramMenuFolder') + @(Get-MsiRows $Database 'Directory' @('Directory','Directory_Parent') |
        Where-Object { $_.Directory_Parent -eq 'ProgramMenuFolder' } | ForEach-Object { $_.Directory })
    $shortcuts = @(Get-MsiRows $Database 'Shortcut' @('Shortcut','Directory_','Name','Target','Arguments') | Where-Object {
        (($_.Name -split '\|')[-1] -in @('Chatbox', 'Chat Box')) -and $_.Target -ceq $target -and
        -not $_.Arguments -and $menuDirs -contains $_.Directory_
    })
    if ($shortcuts.Count -ne 1) { throw 'Expected one Start menu shortcut targeting Chatbox.exe.' }

    # Retain the launcher, upgrade identity and shortcut row ID used for notifications.
    Set-MsiProperty $Database 'ProductName' 'Chat Box'
    $shortcutId = ConvertTo-MsiSqlLiteral $shortcuts[0].Shortcut
    Invoke-MsiSql $Database "UPDATE ``Shortcut`` SET ``Name``='Chatbox|Chat Box' WHERE ``Shortcut``=$shortcutId"

    if (-not (Test-MsiTable $Database 'MsiShortcutProperty')) {
        Invoke-MsiSql $Database 'CREATE TABLE `MsiShortcutProperty` (`MsiShortcutProperty` CHAR(72) NOT NULL, `Shortcut_` CHAR(72) NOT NULL, `PropertyKey` CHAR(0) NOT NULL, `PropVariantValue` CHAR(0) NOT NULL PRIMARY KEY `MsiShortcutProperty`)'
    }
    # A stub CLSID lets Windows persist protocol-activated toasts in Notification Center.
    $properties = @{
        ID = 'Polarzen.Chatbox'
        ToastActivatorCLSID = '{4A8033D8-D9A3-499E-BDC1-EB485352F0DF}'
    }
    foreach ($name in $properties.Keys) {
        Add-MsiRowIfMissing $Database 'MsiShortcutProperty' ([ordered]@{
            MsiShortcutProperty = 'ChatboxReply' + $name
            Shortcut_ = $shortcuts[0].Shortcut
            PropertyKey = 'System.AppUserModel.' + $name
            PropVariantValue = $properties[$name]
        }) @('Shortcut_', 'PropertyKey')
    }

    $protocol = 'Software\Classes\polarzen-chatbox'
    foreach ($entry in @(
        @('ChatboxProtocolDescription', $protocol, $null, 'URL:Chat Box'),
        @('ChatboxProtocolMarker', $protocol, 'URL Protocol', $null),
        @('ChatboxProtocolCommand', ($protocol + '\shell\open\command'), $null, ('"' + $target + '" --open-toast "%1"'))
    )) {
        Add-MsiRowIfMissing $Database 'Registry' ([ordered]@{
            Registry = $entry[0]
            Root = 2
            Key = $entry[1]
            Name = $entry[2]
            Value = $entry[3]
            Component_ = $launcher.Component_
        }) @('Root', 'Key', 'Name')
    }
}

function Add-FullUninstallShortcut($Database) {
    $files = @(Get-MsiRows $Database 'File' @('File', 'Component_', 'FileName'))
    $components = @(Get-MsiRows $Database 'Component' @('Component', 'Directory_'))
    $tracked = @{}
    foreach ($name in @('Chatbox.exe', 'uninstall.cmd', 'Uninstall-ChatBox.ps1', 'Uninstall-ChatBoxData.ps1')) {
        $matches = @($files | Where-Object { ($_.FileName -split '\|')[-1] -ceq $name })
        if ($matches.Count -ne 1) { throw "Expected one tracked $name for complete uninstall." }
        $component = @($components | Where-Object { $_.Component -ceq $matches[0].Component_ })
        if ($component.Count -ne 1) { throw "Expected one component for $name." }
        $tracked[$name] = [pscustomobject]@{ File = $matches[0]; Directory = $component[0].Directory_ }
    }
    $uninstallDirectory = $tracked['uninstall.cmd'].Directory
    foreach ($name in @('Uninstall-ChatBox.ps1', 'Uninstall-ChatBoxData.ps1')) {
        if ($tracked[$name].Directory -cne $uninstallDirectory) {
            throw "$name must be installed beside uninstall.cmd."
        }
    }
    $directories = @(Get-MsiRows $Database 'Directory' @('Directory', 'Directory_Parent', 'DefaultDir'))
    $scriptDirectory = @($directories | Where-Object { $_.Directory -ceq $uninstallDirectory })
    if ($scriptDirectory.Count -ne 1 -or (($scriptDirectory[0].DefaultDir -split ':')[0] -split '\|')[-1] -ine 'uninstall') {
        throw 'Expected the uninstall scripts in the app/uninstall payload directory.'
    }
    $appDirectory = @($directories | Where-Object { $_.Directory -ceq $scriptDirectory[0].Directory_Parent })
    if ($appDirectory.Count -ne 1 -or (($appDirectory[0].DefaultDir -split ':')[0] -split '\|')[-1] -ine 'app' -or
        $appDirectory[0].Directory_Parent -cne $tracked['Chatbox.exe'].Directory) {
        throw 'The uninstall script directory is not under the installed Chat Box app payload.'
    }
    $launcherTarget = '[#' + $tracked['Chatbox.exe'].File.File + ']'
    $mainShortcuts = @(Get-MsiRows $Database 'Shortcut' @('Shortcut', 'Directory_', 'Name', 'Target', 'Arguments') | Where-Object {
        (($_.Name -split '\|')[-1] -eq 'Chat Box') -and $_.Target -ceq $launcherTarget -and -not $_.Arguments
    })
    if ($mainShortcuts.Count -ne 1) { throw 'Expected one Chat Box Start menu shortcut before adding complete uninstall.' }

    # This explicit entry owns data erasure. MSI removal during an upgrade never runs it.
    Add-MsiRowIfMissing $Database 'Shortcut' ([ordered]@{
        Shortcut = 'ChatboxFullUninstall'
        Directory_ = $mainShortcuts[0].Directory_
        Name = 'UninstCB|Uninstall Chat Box'
        Component_ = $tracked['uninstall.cmd'].File.Component_
        Target = '[#' + $tracked['uninstall.cmd'].File.File + ']'
        Arguments = $null
        Description = 'Uninstall Chat Box and permanently delete local data for the current Windows user.'
        ShowCmd = 1
        WkDir = $uninstallDirectory
    }) @('Directory_', 'Name')
}

$installer = $null
$database = $null
try {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase($MsiPath, 1)

    if ((Get-MsiProperty $database 'ProductVersion') -ne $ExpectedVersion) {
        throw "MSI ProductVersion must be '$ExpectedVersion'."
    }
    if ((Get-MsiProperty $database 'UpgradeCode') -ne $expectedUpgradeCode) {
        throw 'MSI UpgradeCode does not match the established Chatbox upgrade UUID.'
    }

    foreach ($table in @('Property', 'Registry', 'RegLocator', 'AppSearch', 'InstallExecuteSequence', 'InstallUISequence')) {
        if (-not (Test-MsiTable $database $table)) {
            throw "Required MSI table '$table' is missing."
        }
    }

    # Only remove the generated recursive cleanup row when it resolves to INSTALLDIR.
    $cleanupRows = @()
    if (Test-MsiTable $database 'WixRemoveFolderEx') {
        $cleanupRows = @(Get-MsiRows $database 'WixRemoveFolderEx' @('WixRemoveFolderEx', 'Component_', 'Property', 'InstallMode'))
    }
    if ($cleanupRows.Count -gt 1) {
        throw "Expected at most one WixRemoveFolderEx row, found $($cleanupRows.Count)."
    }
    foreach ($cleanup in $cleanupRows) {
        if ($cleanup.InstallMode -ne 2) {
            throw "Unexpected recursive cleanup InstallMode '$($cleanup.InstallMode)'."
        }
        $registryRows = @(Get-MsiRows $database 'Registry' @('Registry', 'Root', 'Key', 'Name', 'Value', 'Component_') | Where-Object {
            $_.Name -eq $cleanup.Property -and $_.Component_ -eq $cleanup.Component_
        })
        if ($registryRows.Count -ne 1 -or $registryRows[0].Root -ne 2 -or $registryRows[0].Value -ne '[INSTALLDIR]') {
            throw 'The generated recursive cleanup row does not map exactly to the Chatbox INSTALLDIR registry value.'
        }
    }

    if (-not (Test-MsiTable $database 'LaunchCondition')) {
        Invoke-MsiSql $database 'CREATE TABLE `LaunchCondition` (`Condition` CHAR(255) NOT NULL, `Description` CHAR(255) NOT NULL LOCALIZABLE PRIMARY KEY `Condition`)'
    }

    $existingRegSearch = @(Get-MsiRows $database 'RegLocator' @('Signature_', 'Root', 'Key', 'Name', 'Type') | Where-Object { $_.Signature_ -eq $guardSignature })
    if ($existingRegSearch.Count -eq 0) {
        if (Test-MsiTable $database 'Signature') {
            $signatureCollisions = @(Get-MsiRows $database 'Signature' @('Signature') | Where-Object { $_.Signature -eq $guardSignature })
            if ($signatureCollisions.Count -gt 0) {
                throw "Registry search signature '$guardSignature' collides with a file signature."
            }
        }
        $key = ConvertTo-MsiSqlLiteral $legacyKey
        $name = ConvertTo-MsiSqlLiteral $legacyValue
        $signature = ConvertTo-MsiSqlLiteral $guardSignature
        Invoke-MsiSql $database "INSERT INTO ``RegLocator`` (``Signature_``, ``Root``, ``Key``, ``Name``, ``Type``) VALUES ($signature, 2, $key, $name, 18)"
    }
    elseif ($existingRegSearch.Count -ne 1 -or $existingRegSearch[0].Root -ne 2 -or $existingRegSearch[0].Key -ne $legacyKey -or $existingRegSearch[0].Name -ne $legacyValue -or $existingRegSearch[0].Type -ne 18) {
        throw "Registry search signature '$guardSignature' already exists with unexpected contents."
    }

    $existingAppSearch = @(Get-MsiRows $database 'AppSearch' @('Property', 'Signature_') | Where-Object { $_.Property -eq $guardProperty })
    if ($existingAppSearch.Count -eq 0) {
        $property = ConvertTo-MsiSqlLiteral $guardProperty
        $signature = ConvertTo-MsiSqlLiteral $guardSignature
        Invoke-MsiSql $database "INSERT INTO ``AppSearch`` (``Property``, ``Signature_``) VALUES ($property, $signature)"
    }
    elseif ($existingAppSearch.Count -ne 1 -or $existingAppSearch[0].Signature_ -ne $guardSignature) {
        throw "AppSearch property '$guardProperty' already exists with unexpected contents."
    }

    $conditions = @(Get-MsiRows $database 'LaunchCondition' @('Condition', 'Description'))
    $existingCondition = @($conditions | Where-Object { $_.Condition -eq $guardCondition })
    if ($existingCondition.Count -eq 0) {
        $conditionLiteral = ConvertTo-MsiSqlLiteral $guardCondition
        $messageLiteral = ConvertTo-MsiSqlLiteral $guardMessage
        Invoke-MsiSql $database "INSERT INTO ``LaunchCondition`` (``Condition``, ``Description``) VALUES ($conditionLiteral, $messageLiteral)"
    }
    elseif ($existingCondition.Count -ne 1 -or $existingCondition[0].Description -ne $guardMessage) {
        throw 'The Chatbox 0.1.1 safety launch condition exists with unexpected contents.'
    }

    $securePropertyParts = @((Get-MsiProperty $database 'SecureCustomProperties') -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($property in @($guardProperty, $overrideProperty)) {
        if ($securePropertyParts -notcontains $property) {
            $securePropertyParts += $property
        }
    }
    Set-MsiProperty $database 'SecureCustomProperties' ($securePropertyParts -join ';')

    # This product owns a complete private runtime bundle; force its packaged files to be installed on upgrade.
    Set-MsiProperty $database 'REINSTALLMODE' 'amus'

    foreach ($sequenceTable in @('InstallUISequence', 'InstallExecuteSequence')) {
        $sequenceRows = @(Get-MsiRows $database $sequenceTable @('Action', 'Condition', 'Sequence'))
        $appSearchAction = @($sequenceRows | Where-Object { $_.Action -eq 'AppSearch' })
        $validateAction = @($sequenceRows | Where-Object { $_.Action -eq 'ValidateProductID' })
        if ($appSearchAction.Count -ne 1 -or $validateAction.Count -ne 1 -or $appSearchAction[0].Sequence -ge 60 -or $validateAction[0].Sequence -le 60) {
            throw "Cannot place LaunchConditions safely after AppSearch in '$sequenceTable'."
        }
        Set-MsiSequence $database $sequenceTable 'LaunchConditions' 60
    }

    $executeRows = @(Get-MsiRows $database 'InstallExecuteSequence' @('Action', 'Condition', 'Sequence'))
    $initializeAction = @($executeRows | Where-Object { $_.Action -eq 'InstallInitialize' })
    $removeExistingAction = @($executeRows | Where-Object { $_.Action -eq 'RemoveExistingProducts' })
    $processComponentsAction = @($executeRows | Where-Object { $_.Action -eq 'ProcessComponents' })
    if ($initializeAction.Count -ne 1 -or $removeExistingAction.Count -ne 1 -or $processComponentsAction.Count -ne 1) {
        throw 'Expected exactly one InstallInitialize, RemoveExistingProducts, and ProcessComponents action.'
    }
    $newRemoveSequence = [int]$initializeAction[0].Sequence + 1
    if ($newRemoveSequence -ge $processComponentsAction[0].Sequence) {
        throw 'No safe position exists after InstallInitialize and before ProcessComponents.'
    }
    Set-MsiSequence $database 'InstallExecuteSequence' 'RemoveExistingProducts' $newRemoveSequence

    foreach ($cleanup in $cleanupRows) {
        $cleanupId = ConvertTo-MsiSqlLiteral ([string]$cleanup.WixRemoveFolderEx)
        Invoke-MsiSql $database "DELETE FROM ``WixRemoveFolderEx`` WHERE ``WixRemoveFolderEx``=$cleanupId"
    }

    # The generated action only services WixRemoveFolderEx rows; keep tracked-file removal intact.
    foreach ($sequenceTable in @('InstallUISequence', 'InstallExecuteSequence')) {
        if (Test-MsiTable $database $sequenceTable) {
            Invoke-MsiSql $database "DELETE FROM ``$sequenceTable`` WHERE ``Action``='WixRemoveFoldersEx'"
        }
    }

    $remainingCleanupRows = @()
    if (Test-MsiTable $database 'WixRemoveFolderEx') {
        $remainingCleanupRows = @(Get-MsiRows $database 'WixRemoveFolderEx' @('WixRemoveFolderEx', 'Component_', 'Property', 'InstallMode'))
    }
    if ($remainingCleanupRows.Count -ne 0) {
        throw 'Recursive uninstall cleanup rows remain in the new MSI.'
    }

    $finalCondition = @(Get-MsiRows $database 'LaunchCondition' @('Condition', 'Description') | Where-Object { $_.Condition -eq $guardCondition -and $_.Description -eq $guardMessage })
    $finalSearch = @(Get-MsiRows $database 'AppSearch' @('Property', 'Signature_') | Where-Object { $_.Property -eq $guardProperty -and $_.Signature_ -eq $guardSignature })
    $finalLocator = @(Get-MsiRows $database 'RegLocator' @('Signature_', 'Root', 'Key', 'Name', 'Type') | Where-Object { $_.Signature_ -eq $guardSignature -and $_.Root -eq 2 -and $_.Key -eq $legacyKey -and $_.Name -eq $legacyValue -and $_.Type -eq 18 })
    $finalExecute = @(Get-MsiRows $database 'InstallExecuteSequence' @('Action', 'Condition', 'Sequence'))
    $finalUi = @(Get-MsiRows $database 'InstallUISequence' @('Action', 'Condition', 'Sequence'))
    $finalRemove = @($finalExecute | Where-Object { $_.Action -eq 'RemoveExistingProducts' })
    $finalExecuteLaunch = @($finalExecute | Where-Object { $_.Action -eq 'LaunchConditions' })
    $finalUiLaunch = @($finalUi | Where-Object { $_.Action -eq 'LaunchConditions' })
    $remainingRecursiveActions = @($finalExecute | Where-Object { $_.Action -eq 'WixRemoveFoldersEx' }) + @($finalUi | Where-Object { $_.Action -eq 'WixRemoveFoldersEx' })
    if ($finalCondition.Count -ne 1 -or $finalSearch.Count -ne 1 -or $finalLocator.Count -ne 1 -or $finalRemove.Count -ne 1 -or $finalRemove[0].Sequence -ne $newRemoveSequence -or $finalExecuteLaunch.Count -ne 1 -or $finalExecuteLaunch[0].Sequence -ne 60 -or $finalUiLaunch.Count -ne 1 -or $finalUiLaunch[0].Sequence -ne 60 -or $remainingRecursiveActions.Count -ne 0) {
        throw 'Final MSI safety table validation failed.'
    }
    if ((Get-MsiProperty $database 'REINSTALLMODE') -ne 'amus') {
        throw "Final MSI REINSTALLMODE property validation failed."
    }

    Add-ReplyToastRegistration $database
    Add-FullUninstallShortcut $database
    [void]$database.Commit()
    Write-Output "Patched MSI database: $MsiPath"
    Write-Output "  ProductVersion: $ExpectedVersion"
    Write-Output '  REINSTALLMODE: amus'
    Write-Output '  Recursive uninstall rows/actions: 0'
    Write-Output '  Complete uninstall: tracked scripts and explicit Start menu entry'
    Write-Output "  Legacy 0.1.1 guard: $guardCondition"
    Write-Output "  RemoveExistingProducts sequence: $newRemoveSequence"
}
finally {
    if ($null -ne $database) {
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
    }
    if ($null -ne $installer) {
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
    }
}
