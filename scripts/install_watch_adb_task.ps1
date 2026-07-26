<#
.SYNOPSIS
    Registers the watch network-ADB keepalive as a Windows scheduled task.

.DESCRIPTION
    Creates (or replaces) the `SuixinWatchAdbKeepalive` task so the network ADB
    endpoint is re-established at logon and stays healthy for the whole session.
    The task runs hidden under the current user, no elevation required.

.PARAMETER Remove
    Unregister the task instead of installing it.
#>
[CmdletBinding()]
param(
    [switch]$Remove,
    [int]$Port = 5555,
    [int]$IntervalSeconds = 20
)

$ErrorActionPreference = 'Stop'
$taskName = 'SuixinWatchAdbKeepalive'
$script = Join-Path $PSScriptRoot 'watch_adb_keepalive.ps1'

if ($Remove) {
    if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        Write-Host "removed scheduled task $taskName"
    } else {
        Write-Host "scheduled task $taskName not present"
    }
    return
}

if (-not (Test-Path $script)) { throw "keepalive script missing: $script" }

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument (
    '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}" -Port {1} -IntervalSeconds {2}' -f $script, $Port, $IntervalSeconds
)
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal -Force | Out-Null
Write-Host "registered scheduled task $taskName"

Start-ScheduledTask -TaskName $taskName
Start-Sleep -Seconds 3
Get-ScheduledTask -TaskName $taskName | Get-ScheduledTaskInfo |
    Select-Object TaskName, LastRunTime, LastTaskResult, NumberOfMissedRuns | Format-List
