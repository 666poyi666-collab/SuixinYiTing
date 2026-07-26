<#
.SYNOPSIS
    Offline unit tests for the network-bridge pure-logic classes.

.DESCRIPTION
    The bridge is normally compiled with javac and dexed for the watch; there is
    no gradle and no device in this loop. This script compiles the bridge main
    sources plus the JUnit tests against android.jar and runs them on the desktop
    JVM. Only classes that either avoid Android APIs or depend on them solely
    through interfaces (which the tests fake in-memory) are exercised, so no
    android.jar stub method is ever called at runtime.

    Requires the same JDK the build uses; downloads JUnit 4 + Hamcrest once.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$module = Join-Path $root 'network-bridge'
$src = Join-Path $module 'src'
$test = Join-Path $module 'test'
$deps = Join-Path $module 'deps'
$out = Join-Path $module 'build/test-classes'

$localEnv = Join-Path $PSScriptRoot 'local-build-env.ps1'
if (Test-Path $localEnv) { . $localEnv }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else { throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT' }
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'

New-Item -ItemType Directory -Force $deps, $out | Out-Null

function Get-Dep($name, $url) {
    $path = Join-Path $deps $name
    if (-not (Test-Path $path)) {
        Write-Host "downloading $name"
        Invoke-WebRequest $url -OutFile $path
    }
    return $path
}
$junit = Get-Dep 'junit-4.13.2.jar' 'https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar'
$hamcrest = Get-Dep 'hamcrest-core-1.3.jar' 'https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar'

$sep = [IO.Path]::PathSeparator
$cp = @($androidJar, $junit, $hamcrest) -join $sep

Get-ChildItem $out -Recurse -File -ErrorAction SilentlyContinue | Remove-Item -Force
$mainSrc = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object FullName
$testSrc = Get-ChildItem $test -Recurse -Filter *.java | ForEach-Object FullName

# The bridge references androidx/okhttp/media3 at compile time; test only pulls
# the pure classes, but javac still needs the full classpath to resolve the
# main sources it must compile alongside the tests.
$recyclerJar = Join-Path $deps 'recyclerview-1.3.2-classes.jar'
$coreJar = Join-Path $deps 'core-1.12.0-classes.jar'
$zxing = Join-Path $deps 'core-3.5.3.jar'
$compileCp = @($androidJar, $junit, $hamcrest, $recyclerJar, $coreJar, $zxing) -join $sep

& javac -encoding UTF-8 -source 8 -target 8 -classpath $compileCp -d $out @($mainSrc + $testSrc)
if ($LASTEXITCODE -ne 0) { throw 'test compile failed' }

$runCp = @($out, $androidJar, $junit, $hamcrest) -join $sep
$suites = @(
    'com.poyi.suixinyiting.network.ShuffleBagTest',
    'com.poyi.suixinyiting.network.QualityPolicyTest',
    'com.poyi.suixinyiting.network.AudioCacheKeyTest',
    'com.poyi.suixinyiting.network.IdCodecTest',
    'com.poyi.suixinyiting.network.LyricIndexTest',
    'com.poyi.suixinyiting.network.LrcParserTest',
    'com.poyi.suixinyiting.network.CookiesTest'
)
& java -classpath $runCp org.junit.runner.JUnitCore @suites
exit $LASTEXITCODE
