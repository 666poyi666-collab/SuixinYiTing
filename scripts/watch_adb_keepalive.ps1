<#
.SYNOPSIS
    Keeps network ADB to the OWW221 watch permanently available.

.DESCRIPTION
    The watch runs a production `user` build, so `persist.adb.tcp.port` cannot be
    written and Android 11 wireless debugging (`adb_wifi_enabled`) is rejected by
    the firmware. Network ADB therefore has to be re-armed from the PC side:

      * while the watch is reachable over TCP the loop only health-checks it;
      * when the TCP transport drops it reconnects to the last known address;
      * when USB is attached it re-runs `adb tcpip` and re-reads wlan0, which is
        what recovers the link after a watch reboot;
      * the discovered address is cached so a reconnect works with no USB cable.

.PARAMETER Port
    TCP port adbd listens on. Default 5555.

.PARAMETER IntervalSeconds
    Health-check period. Default 20.

.PARAMETER Once
    Run a single reconcile pass and exit. Used by the installer for a smoke test.
#>
[CmdletBinding()]
param(
    [int]$Port = 5555,
    [int]$IntervalSeconds = 20,
    [switch]$Once
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$stateDir = Join-Path $root 'artifacts/adb'
$stateFile = Join-Path $stateDir 'watch-endpoint.json'
$logFile = Join-Path $stateDir 'watch_adb_keepalive.log'
$null = New-Item -ItemType Directory -Force -Path $stateDir

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    Write-Host $line
    try {
        if ((Test-Path $logFile) -and ((Get-Item $logFile).Length -gt 1MB)) {
            Move-Item $logFile "$logFile.1" -Force
        }
        Add-Content -Path $logFile -Value $line -Encoding UTF8
    } catch { }
}

function Invoke-Adb {
    param([string[]]$AdbArgs, [int]$TimeoutSeconds = 25)
    # Windows PowerShell 5.1 has no ProcessStartInfo.ArgumentList, so quote manually.
    $quoted = ($AdbArgs | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join ' '
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = 'adb'
    $psi.Arguments = $quoted
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    try { $p = [Diagnostics.Process]::Start($psi) } catch { return [pscustomobject]@{ Ok = $false; Out = "adb not found: $_" } }
    if (-not $p.WaitForExit($TimeoutSeconds * 1000)) {
        try { $p.Kill() } catch { }
        return [pscustomobject]@{ Ok = $false; Out = 'timeout' }
    }
    $out = ($p.StandardOutput.ReadToEnd() + $p.StandardError.ReadToEnd()).Trim()
    return [pscustomobject]@{ Ok = ($p.ExitCode -eq 0); Out = $out }
}

function Get-Transports {
    $r = Invoke-Adb @('devices')
    $list = @()
    foreach ($line in ($r.Out -split "`r?`n")) {
        if ($line -match '^(\S+)\s+(device|offline|unauthorized)\s*$') {
            $list += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    }
    return , $list
}

function Get-UsbSerial {
    foreach ($t in (Get-Transports)) {
        if ($t.State -eq 'device' -and $t.Serial -notmatch ':\d+$') { return $t.Serial }
    }
    return $null
}

function Test-Endpoint {
    param([string]$Endpoint)
    if (-not $Endpoint) { return $false }
    foreach ($t in (Get-Transports)) {
        if ($t.Serial -eq $Endpoint -and $t.State -eq 'device') {
            # `adb devices` can keep a stale entry; prove the shell still answers.
            $probe = Invoke-Adb @('-s', $Endpoint, 'shell', 'echo', 'ok') 10
            return ($probe.Ok -and $probe.Out -match 'ok')
        }
    }
    return $false
}

function Get-SavedEndpoint {
    if (-not (Test-Path $stateFile)) { return $null }
    try { return (Get-Content $stateFile -Raw | ConvertFrom-Json).endpoint } catch { return $null }
}

function Save-Endpoint {
    param([string]$Endpoint)
    [pscustomobject]@{
        endpoint = $Endpoint
        updated  = (Get-Date -Format 'o')
    } | ConvertTo-Json | Set-Content -Path $stateFile -Encoding UTF8
}

function Get-WatchAddress {
    param([string]$Serial)
    $r = Invoke-Adb @('-s', $Serial, 'shell', 'ip', '-f', 'inet', 'addr', 'show', 'wlan0')
    if ($r.Out -match 'inet\s+(\d+\.\d+\.\d+\.\d+)') { return $Matches[1] }
    return $null
}

function Connect-OverUsb {
    $serial = Get-UsbSerial
    if (-not $serial) { return $null }
    $ip = Get-WatchAddress -Serial $serial
    if (-not $ip) {
        Write-Log "USB transport $serial is up but wlan0 has no IPv4 address yet" 'WARN'
        return $null
    }
    Write-Log "re-arming adbd over USB ($serial -> ${ip}:$Port)"
    $null = Invoke-Adb @('-s', $serial, 'tcpip', "$Port")
    Start-Sleep -Seconds 3
    $endpoint = "${ip}:$Port"
    $c = Invoke-Adb @('connect', $endpoint)
    Write-Log "connect $endpoint -> $($c.Out)"
    if (Test-Endpoint $endpoint) { return $endpoint }
    return $null
}

function Connect-OverNetwork {
    param([string]$Endpoint)
    if (-not $Endpoint) { return $false }
    $null = Invoke-Adb @('disconnect', $Endpoint) 10
    $c = Invoke-Adb @('connect', $Endpoint)
    if ($c.Out -match 'connected to') {
        if (Test-Endpoint $Endpoint) {
            Write-Log "reconnected $Endpoint"
            return $true
        }
    }
    return $false
}

function Invoke-Reconcile {
    $endpoint = Get-SavedEndpoint
    if (Test-Endpoint $endpoint) { return $endpoint }

    if ($endpoint -and (Connect-OverNetwork -Endpoint $endpoint)) { return $endpoint }

    $fresh = Connect-OverUsb
    if ($fresh) {
        Save-Endpoint -Endpoint $fresh
        Write-Log "network ADB armed at $fresh"
        return $fresh
    }

    # Watch reachable on a new DHCP lease: retry the saved subnet's live hosts.
    if ($endpoint -and $endpoint -match '^(\d+\.\d+\.\d+)\.\d+:') {
        $prefix = $Matches[1]
        foreach ($line in (arp -a 2>$null)) {
            if ($line -match "($([regex]::Escape($prefix))\.\d+)\s") {
                $candidate = "$($Matches[1]):$Port"
                if ($candidate -eq $endpoint) { continue }
                $c = Invoke-Adb @('connect', $candidate) 8
                if ($c.Out -match 'connected to' -and (Test-Endpoint $candidate)) {
                    Save-Endpoint -Endpoint $candidate
                    Write-Log "watch moved to $candidate"
                    return $candidate
                }
                $null = Invoke-Adb @('disconnect', $candidate) 5
            }
        }
    }
    return $null
}

Write-Log "keepalive starting (port=$Port interval=${IntervalSeconds}s once=$Once)"
$lastHealthy = $null
while ($true) {
    try {
        $endpoint = Invoke-Reconcile
        if ($endpoint) {
            if ($endpoint -ne $lastHealthy) {
                Write-Log "watch online at $endpoint"
                $lastHealthy = $endpoint
            }
        } else {
            if ($lastHealthy) { Write-Log 'watch offline; waiting for Wi-Fi or USB' 'WARN' }
            $lastHealthy = $null
        }
    } catch {
        Write-Log "reconcile failed: $_" 'ERROR'
    }
    if ($Once) { break }
    Start-Sleep -Seconds $IntervalSeconds
}
