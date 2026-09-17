# verify-env.ps1 - Optique build environment check (Windows)
# Run: powershell -File scripts\verify-env.ps1
# Exits 0 if the environment can build, 1 with a list of missing pieces.

$failures = @()

# 1. JDK (17 or 21 - AGP for compileSdk 37 accepts both)
$javaOut = & java -version 2>&1 | Select-Object -First 1
if ($javaOut -match '"(\d+)') {
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -notin @(17, 21)) { $failures += "JDK $javaMajor detected - need JDK 17 or 21" }
    else { Write-Host "[OK] JDK $javaMajor" }
} else { $failures += "java not on PATH" }

# 2. Android SDK
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk -or -not (Test-Path $sdk)) {
    $failures += "ANDROID_HOME / ANDROID_SDK_ROOT not set"
    $sdk = "$env:LOCALAPPDATA\Android\Sdk"
    if (-not (Test-Path $sdk)) { $sdk = $null }
}
if ($sdk) {
    Write-Host "[OK] Android SDK: $sdk"

    # 3. Platform android-37
    if ((Test-Path "$sdk\platforms\android-37.0") -or (Test-Path "$sdk\platforms\android-37")) {
        Write-Host "[OK] Platform android-37"
    } else { $failures += "Missing SDK platform android-37" }

    # 4. NDK (must match refra.ndkVersion in gradle.properties)
    $props = Get-Content .\gradle.properties -ErrorAction SilentlyContinue
    $ndkLine = $props | Where-Object { $_ -match '^refra\.ndkVersion=(.+)' }
    if ($ndkLine -match '=(.+)') {
        $ndkVer = $Matches[1].Trim()
        if (Test-Path "$sdk\ndk\$ndkVer") { Write-Host "[OK] NDK $ndkVer" }
        else { $failures += "NDK $ndkVer not found in $sdk\ndk" }
    } else { $failures += "refra.ndkVersion missing from gradle.properties" }

    # 5. CMake 3.31.x (4.x breaks libde265/libheif - do NOT install CMake 4)
    $cmakeDir = Get-ChildItem "$sdk\cmake" -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "3.31.*" } | Select-Object -First 1
    if ($cmakeDir) { Write-Host "[OK] CMake $($cmakeDir.Name)" }
    else { $failures += "CMake 3.31.x not found in $sdk\cmake (install 3.31.6 via sdkmanager)" }

    # 6. Build tools
    if (Test-Path "$sdk\build-tools") { Write-Host "[OK] build-tools dir" }
    else { $failures += "build-tools missing" }
}

# 7. Signing (release only - warn, not fail)
if (-not (Test-Path ".\app\release_key.jks")) {
    Write-Host "[WARN] app\release_key.jks absent - debug builds fine, release/bundleRelease needs SIGNING_* env vars + keystore"
}

if ($failures.Count -eq 0) {
    Write-Host "`nEnvironment READY - run: .\gradlew assembleDebug"
    exit 0
} else {
    Write-Host "`nEnvironment INCOMPLETE:"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
