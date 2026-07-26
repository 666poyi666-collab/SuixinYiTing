<#
.SYNOPSIS
    Run the whole offline test suite: bridge unit tests + patch pipeline tests.

.DESCRIPTION
    Neither suite needs the watch. Use this before every build/release as the
    regression gate. Exits non-zero if either suite fails.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$failed = $false

Write-Host "== bridge unit tests ==" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'run_bridge_tests.ps1')
if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Host "bridge tests FAILED" -ForegroundColor Red }

Write-Host "`n== patch pipeline tests ==" -ForegroundColor Cyan
$localEnv = Join-Path $PSScriptRoot 'local-build-env.ps1'
if (Test-Path $localEnv) { . $localEnv }
& python -X utf8 (Join-Path $PSScriptRoot 'test_patch_suixin.py')
if ($LASTEXITCODE -ne 0) { $failed = $true; Write-Host "patch tests FAILED" -ForegroundColor Red }

if ($failed) { Write-Host "`nSUITE FAILED" -ForegroundColor Red; exit 1 }
Write-Host "`nALL TESTS PASSED" -ForegroundColor Green
