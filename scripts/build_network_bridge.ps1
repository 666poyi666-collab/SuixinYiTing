$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$module = Join-Path $root 'network-bridge'
$build = Join-Path $module 'build'
$classes = Join-Path $build 'classes'
$deps = Join-Path $module 'deps'
# Machine-specific SDK path and signing credentials live in a git-ignored file
# so nothing environment-specific is ever committed.
$localEnv = Join-Path $PSScriptRoot 'local-build-env.ps1'
if (Test-Path $localEnv) { . $localEnv }

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else { throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT (see scripts/local-build-env.example.ps1)' }
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'
$d8 = Join-Path $sdk 'build-tools\35.0.0\d8.bat'

New-Item -ItemType Directory -Force $classes,$deps | Out-Null
$zxing = Join-Path $deps 'core-3.5.3.jar'
if (-not (Test-Path $zxing)) {
    Invoke-WebRequest 'https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar' -OutFile $zxing
}
$recyclerAar = Join-Path $deps 'recyclerview-1.3.2.aar'
$recyclerJar = Join-Path $deps 'recyclerview-1.3.2-classes.jar'
if (-not (Test-Path $recyclerAar)) {
    Invoke-WebRequest 'https://dl.google.com/dl/android/maven2/androidx/recyclerview/recyclerview/1.3.2/recyclerview-1.3.2.aar' -OutFile $recyclerAar
}
if (-not (Test-Path $recyclerJar)) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($recyclerAar)
    try {
        $entry = $archive.GetEntry('classes.jar')
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $recyclerJar, $true)
    } finally { $archive.Dispose() }
}
$coreAar = Join-Path $deps 'core-1.12.0.aar'
$coreJar = Join-Path $deps 'core-1.12.0-classes.jar'
if (-not (Test-Path $coreAar)) {
    Invoke-WebRequest 'https://dl.google.com/dl/android/maven2/androidx/core/core/1.12.0/core-1.12.0.aar' -OutFile $coreAar
}
if (-not (Test-Path $coreJar)) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($coreAar)
    try {
        $entry = $archive.GetEntry('classes.jar')
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $coreJar, $true)
    } finally { $archive.Dispose() }
}
Get-ChildItem $classes -Recurse -File -ErrorAction SilentlyContinue | Remove-Item -Force
$sources = Get-ChildItem (Join-Path $module 'src') -Recurse -Filter *.java | ForEach-Object FullName
& javac -encoding UTF-8 -source 8 -target 8 -classpath "$androidJar;$zxing;$recyclerJar;$coreJar" -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$dexOut = Join-Path $build 'dex'
if (Test-Path $dexOut) { Remove-Item $dexOut -Recurse -Force }
New-Item -ItemType Directory -Force $dexOut | Out-Null
$classesJar = Join-Path $build 'network-bridge-classes.jar'
if (Test-Path $classesJar) { Remove-Item $classesJar -Force }
& jar cf $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
& $d8 --min-api 23 --lib $androidJar --lib $recyclerJar --lib $coreJar --output $dexOut $classesJar $zxing
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Copy-Item (Join-Path $dexOut 'classes.dex') (Join-Path $build 'classes2.dex') -Force
Write-Host (Join-Path $build 'classes2.dex')
