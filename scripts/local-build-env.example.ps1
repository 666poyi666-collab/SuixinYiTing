# Copy to scripts/local-build-env.ps1 and fill in for this machine.
# local-build-env.ps1 is git-ignored: signing credentials never enter the repo.

$env:ANDROID_HOME = 'C:\Android\Sdk'

# Reuse the keystore the device was already installed with. Signing an upgrade
# with a different key forces an uninstall, which wipes the login and the
# 1242-track library.
$env:SUIXIN_KEYSTORE = 'artifacts\build\suixin-debug.keystore'
$env:SUIXIN_KEY_ALIAS = 'androiddebugkey'
# apksigner reads env:NAME as the raw password, so no 'pass:' prefix here.
$env:SUIXIN_KS_PASS = 'your-keystore-password'
$env:SUIXIN_KEY_PASS = 'your-key-password'
