$ErrorActionPreference = 'Stop'
$outputDir = $PSScriptRoot
$workspace = Split-Path -Parent $outputDir

$sdkCandidates = @()
if ($env:ANDROID_SDK_ROOT) { $sdkCandidates += $env:ANDROID_SDK_ROOT }
if ($env:ANDROID_HOME) { $sdkCandidates += $env:ANDROID_HOME }
if ($env:LOCALAPPDATA) { $sdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
$sdkCandidates += (Join-Path $workspace 'work\tooling\android-sdk')
$sdk = $sdkCandidates | Select-Object -Unique | Where-Object {
    Test-Path (Join-Path $_ 'platforms\android-35\android.jar')
} | Select-Object -First 1
if (-not $sdk) {
    throw 'Android SDK Platform 35 не найден. Установите его через Android Studio и задайте ANDROID_SDK_ROOT.'
}

$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
if ($env:ProgramFiles) { $jdkCandidates += (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr') }
$jdkCandidates += (Join-Path $workspace 'work\tooling\jdk\jdk-17.0.20.1+1')
$jdk = $jdkCandidates | Select-Object -Unique | Where-Object {
    Test-Path (Join-Path $_ 'bin\java.exe')
} | Select-Object -First 1
if (-not $jdk) {
    throw 'JDK не найден. Установите JDK 17 и задайте JAVA_HOME.'
}

$taskTemp = Join-Path $outputDir 'android\.tmp'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$env:TEMP = $taskTemp
$env:TMP = $taskTemp
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue

# Reuse the workspace cache when present; otherwise Gradle uses its normal user cache.
$cachedGradleHome = Join-Path $workspace 'work\tooling\gradle-home'
if (Test-Path $cachedGradleHome) { $env:GRADLE_USER_HOME = $cachedGradleHome }

$project = Join-Path $outputDir 'android'
Push-Location $project
try {
    & .\gradlew.bat --no-daemon --max-workers=1 assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle завершился с кодом $LASTEXITCODE." }
    $apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { throw "Gradle завершился без APK: $apk" }
    $deliverable = Join-Path $outputDir 'ContextShield-Android-debug.apk'
    Copy-Item -LiteralPath $apk -Destination $deliverable -Force
    Write-Host "APK собран: $deliverable"
}
finally { Pop-Location }
