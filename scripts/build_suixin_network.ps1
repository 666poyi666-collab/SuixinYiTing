$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

python -X utf8 scripts\patch_suixin.py work\suixin-apktool
if ($LASTEXITCODE -ne 0) { throw 'patch failed' }
& scripts\build_network_bridge.ps1

java -jar tools\apktool.jar b work\suixin-apktool -o artifacts\build\suixinyiting-network-unsigned.apk
if ($LASTEXITCODE -ne 0) { throw 'apktool failed' }

$apk = Resolve-Path 'artifacts\build\suixinyiting-network-unsigned.apk'
$dex = Resolve-Path 'network-bridge\build\classes2.dex'
$tmp = "$apk.tmp"
@'
import sys, zipfile
apk, dex, out = sys.argv[1:]
with zipfile.ZipFile(apk, 'r') as src, zipfile.ZipFile(out, 'w') as dst:
    for item in src.infolist():
        if item.filename != 'classes2.dex':
            dst.writestr(item, src.read(item.filename))
    dst.write(dex, 'classes2.dex', compress_type=zipfile.ZIP_DEFLATED)
'@ | python - $apk $dex $tmp
Move-Item -LiteralPath $tmp -Destination $apk -Force

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) {
  $env:ANDROID_SDK_ROOT
} else { throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT' }
$bt = Join-Path $sdk 'build-tools\35.0.0'
$keystore = $env:SUIXIN_KEYSTORE
$keyAlias = $env:SUIXIN_KEY_ALIAS
if (-not $keystore) { throw 'Set SUIXIN_KEYSTORE to your signing keystore path' }
if (-not $keyAlias) { throw 'Set SUIXIN_KEY_ALIAS to your signing key alias' }
if (-not $env:SUIXIN_KS_PASS) { throw 'Set SUIXIN_KS_PASS' }
if (-not $env:SUIXIN_KEY_PASS) { throw 'Set SUIXIN_KEY_PASS' }
& "$bt\zipalign.exe" -f -p 4 $apk artifacts\build\suixinyiting-network-aligned.apk
& "$bt\apksigner.bat" sign --ks $keystore `
  --ks-pass env:SUIXIN_KS_PASS --key-pass env:SUIXIN_KEY_PASS --ks-key-alias $keyAlias `
  --out artifacts\build\suixinyiting-network-1.2.9.apk artifacts\build\suixinyiting-network-aligned.apk
& "$bt\apksigner.bat" verify --verbose artifacts\build\suixinyiting-network-1.2.9.apk
Get-FileHash artifacts\build\suixinyiting-network-1.2.9.apk -Algorithm SHA256
