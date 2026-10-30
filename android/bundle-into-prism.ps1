# Builds this app's release APK and puts it in Prism Launcher's start module, which carries it and
# installs or updates it on the phone (RcsLink.kt there). The app stays a separate program under
# the AGPL: anyone given Prism gets this app's source offered with it (its about page, and Prism's
# install prompt, link to it). Push the commit this APK was built from before handing Prism out.
param(
    [string]$Prism = "$PSScriptRoot\..\..\phonexr\start"
)
$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    .\gradlew.bat --console=plain assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
} finally {
    Pop-Location
}
$apk = "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk"
$meta = Get-Content "$PSScriptRoot\app\build\outputs\apk\release\output-metadata.json" | ConvertFrom-Json
$version = $meta.elements[0].versionCode
$assets = Join-Path $Prism "src\main\assets\rcs"
New-Item -ItemType Directory -Force $assets | Out-Null
Copy-Item $apk (Join-Path $assets "prism-rcs.apk") -Force
$commit = (git -C $PSScriptRoot rev-parse HEAD).Trim()
# versionCode, then the commit the APK was built from (its corresponding source)
Set-Content -Path (Join-Path $assets "version.txt") -Value "$version`n$commit" -NoNewline
Write-Host "Bundled prism-rcs versionCode $version ($commit) into $assets"
