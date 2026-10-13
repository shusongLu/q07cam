<#
.SYNOPSIS
  构建「热更插件 dex」：编译 plugin 模块 → jar → d8 → plugin.dex（可选直接推到车机）。

.DESCRIPTION
  插件只依赖 core 接口（compileOnly），运行时由壳 APK 提供 core / Android / Kotlin 类。
  输出 tools\out\plugin.dex。

.EXAMPLE
  .\build-plugin-dex.ps1
  .\build-plugin-dex.ps1 -Push -Serial 192.168.0.101:5555
  .\build-plugin-dex.ps1 -Push -Serial 192.168.0.101:5555 -Package com.lushu.q07battery
#>
[CmdletBinding()]
param(
    [switch]$Push,
    [string]$Serial = "",
    [string]$Package = "com.lushu.q07battery",
    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
}
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = "C:\Users\lushu\AppData\Local\Android\Sdk" }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$d8 = Join-Path $sdk "build-tools\37.0.0\d8.bat"
if (-not (Test-Path $d8)) { throw "找不到 d8: $d8" }

Write-Host "[1/3] 构建 plugin.jar …" -ForegroundColor Cyan
& (Join-Path $repo "gradlew.bat") ":plugin:jar" -p $repo --console=plain -q
if ($LASTEXITCODE -ne 0) { throw "plugin jar 构建失败" }

$jar = Join-Path $repo "plugin\build\libs\plugin.jar"
if (-not (Test-Path $jar)) { throw "找不到 $jar" }

Write-Host "[2/3] d8 转换为 dex …" -ForegroundColor Cyan
$outDir = Join-Path $repo "tools\out"
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
& $d8 --min-api 30 --output $outDir $jar
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

$dex = Join-Path $outDir "classes.dex"
if (-not (Test-Path $dex)) { throw "d8 未产出 classes.dex" }
$pluginDex = Join-Path $outDir "plugin.dex"
Copy-Item $dex $pluginDex -Force
Write-Host ("      生成: {0}  ({1} bytes)" -f $pluginDex, (Get-Item $pluginDex).Length) -ForegroundColor Green

if ($Push) {
    Write-Host "[3/3] 推送到车机 …" -ForegroundColor Cyan
    if (-not $Adb) { $Adb = $env:REA_ADB_PATH }
    if (-not $Adb) { $Adb = "adb" }
    $pargs = @()
    if ($Serial) { $pargs += @("-s", $Serial) }
    $target = "/sdcard/Android/data/$Package/files/plugin.dex"
    & $Adb @pargs push $pluginDex $target
    if ($LASTEXITCODE -ne 0) { throw "推送失败：$target" }
    Write-Host "      已推送: $target" -ForegroundColor Green
    Write-Host "      在车机上点「重新加载插件」即可生效。" -ForegroundColor Green
} else {
    Write-Host "[3/3] 跳过推送（加 -Push 可自动 adb push）" -ForegroundColor DarkGray
}
