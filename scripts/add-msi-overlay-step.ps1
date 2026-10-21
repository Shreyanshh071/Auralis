<#
.SYNOPSIS
    Adds the gaming-overlay step to a built Auralis MSI (run by the Gradle MSI tasks).
.DESCRIPTION
    Gaming overlays take OpenGL Auralis for a game: NVIDIA's showed "Press Alt+Z" at every start, and
    RivaTuner draws its frame counter on it. Only an administrator may exclude an app from them, so the
    installer does it: after the files are in place it runs "Auralis.exe --game-overlays-setup", which
    (only when something is left to change on that PC) asks Windows for approval and excludes Auralis.
    See GameOverlayOptOut.kt. jpackage clears its resource folder before building, so the step is added
    to the finished MSI's tables instead of its WiX source.
#>
param([Parameter(Mandatory = $true)][string]$Msi)

$ErrorActionPreference = "Stop"
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.GetType().InvokeMember("OpenDatabase", "InvokeMethod", $null, $installer, @($Msi, 1))

function Invoke-Sql([string]$sql) {
    $view = $database.GetType().InvokeMember("OpenView", "InvokeMethod", $null, $database, @($sql))
    [void]$view.GetType().InvokeMember("Execute", "InvokeMethod", $null, $view, $null)
    $view
}

function Read-Rows([string]$sql, [int]$fields) {
    $view = Invoke-Sql $sql
    $rows = @()
    while ($true) {
        $record = $view.GetType().InvokeMember("Fetch", "InvokeMethod", $null, $view, $null)
        if ($null -eq $record) { break }
        $row = @()
        for ($field = 1; $field -le $fields; $field++) {
            $row += [string]$record.GetType().InvokeMember("StringData", "GetProperty", $null, $record, [object[]]@([int]$field))
        }
        $rows += , $row
    }
    [void]$view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null)
    , $rows
}

$action = "AuralisGameOverlaySetup"
if ((Read-Rows "SELECT ``Action`` FROM ``CustomAction`` WHERE ``Action`` = '$action'" 1).Count -gt 0) {
    Write-Host "Overlay step already in $Msi"
    exit 0
}

# The launcher's File key ("AURALIS.EXE|Auralis.exe" or "Auralis.exe" in FileName).
$exe = (Read-Rows "SELECT ``File``, ``FileName`` FROM ``File``" 2) |
    Where-Object { ($_[1] -split '\|')[-1] -ieq "Auralis.exe" } | Select-Object -First 1
if ($null -eq $exe) { throw "Auralis.exe not found in $Msi" }

# Type 18 (an installed EXE) + 64 (its exit code never fails the install).
$view = Invoke-Sql "INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('$action', 82, '$($exe[0])', '--game-overlays-setup')"
[void]$view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null)
# After InstallFinalize: the files exist. Not on uninstall, and not in silent installs (no one to approve).
$finalize = (Read-Rows "SELECT ``Sequence`` FROM ``InstallExecuteSequence`` WHERE ``Action`` = 'InstallFinalize'" 1)[0][0]
$view = Invoke-Sql "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('$action', 'NOT REMOVE AND UILevel >= 4', $([int]$finalize + 1))"
[void]$view.GetType().InvokeMember("Close", "InvokeMethod", $null, $view, $null)

[void]$database.GetType().InvokeMember("Commit", "InvokeMethod", $null, $database, $null)
[System.Runtime.InteropServices.Marshal]::ReleaseComObject($database) | Out-Null
Write-Host "Added the gaming-overlay step to $Msi"
