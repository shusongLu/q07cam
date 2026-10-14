<#
.SYNOPSIS
  构建「不安装版悬浮窗」的 dex，并（可选）推送到车机。

.DESCRIPTION
  数据走厂商 HAL（com.ca.car.proxy.*，车机自带，无需权限）；
  由 root 通过 app_process 启动，不安装、不改 /system。
  产物：tools\out-overlay\classes.dex

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\tools\build-overlay.ps1
  powershell -ExecutionPolicy Bypass -File .\tools\build-overlay.ps1 -Push -Serial 192.168.137.167:5555
#>
[CmdletBinding()]
param(
    [switch]$Push,
    [string]$Serial = "",
    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent

$jdk = $env:JAVA_HOME
if (-not $jdk) { $jdk = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" }
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = "C:\Users\lushu\AppData\Local\Android\Sdk" }
$plat = Join-Path $sdk "platforms\android-37.0"
$bt = Join-Path $sdk "build-tools\37.0.0"

$src = Join-Path $repo "tools\overlay"
$out = Join-Path $repo "tools\out-overlay"
$classes = Join-Path $out "classes"
$dexDir = Join-Path $out "dex"

foreach ($p in @("$jdk\bin\javac.exe", "$plat\android.jar", "$bt\d8.bat")) {
    if (-not (Test-Path $p)) { throw "缺少: $p" }
}

if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
if (Test-Path $dexDir) { Remove-Item $dexDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes, $dexDir | Out-Null

Write-Host "[1/3] javac …" -ForegroundColor Cyan
& "$jdk\bin\javac.exe" -source 8 -target 8 -nowarn -classpath "$plat\android.jar" -d $classes (Join-Path $src "Main.java")
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "[2/3] d8 → dex …" -ForegroundColor Cyan
& "$jdk\bin\jar.exe" cf (Join-Path $out "overlay.jar") -C $classes .
& "$bt\d8.bat" --min-api 30 --output $dexDir (Join-Path $out "overlay.jar") | Out-Null
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }
$dex = Join-Path $dexDir "classes.dex"
Write-Host ("      生成: {0}  ({1} bytes)" -f $dex, (Get-Item $dex).Length) -ForegroundColor Green

if ($Push) {
    Write-Host "[3/3] 推送到车机 …" -ForegroundColor Cyan
    if (-not $Adb) { $Adb = $env:REA_ADB_PATH }
    if (-not $Adb) { $Adb = "adb" }
    $pargs = @()
    if ($Serial) { $pargs += @("-s", $Serial) }
    & $Adb @pargs push $dex /data/local/tmp/q07overlay.dex
    if ($LASTEXITCODE -ne 0) { throw "推送失败" }
    Write-Host "      已推送: /data/local/tmp/q07overlay.dex" -ForegroundColor Green
} else {
    Write-Host "[3/3] 跳过推送（加 -Push 自动推送）" -ForegroundColor DarkGray
}
