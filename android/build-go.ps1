# Builds app/libs/rcsmobile.aar (libgm for Android) with gomobile.
# Needs Go, gomobile (go install golang.org/x/mobile/cmd/gomobile@latest; gomobile init),
# the Android SDK and an NDK.
$ErrorActionPreference = "Stop"
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $env:ANDROID_NDK_HOME) {
    $env:ANDROID_NDK_HOME = (Get-ChildItem "$env:ANDROID_HOME\ndk" | Sort-Object Name | Select-Object -Last 1).FullName
}
New-Item -ItemType Directory -Force "$PSScriptRoot\app\libs" | Out-Null
Push-Location "$PSScriptRoot\rcsmobile"
try {
    gomobile bind -target=android/arm64,android/amd64 -androidapi 29 -javapkg dev.prism.rcs.go `
        -o "$PSScriptRoot\app\libs\rcsmobile.aar" .
} finally {
    Pop-Location
}
