<#
.SYNOPSIS
  实时盯车机日志：把"车辆信号 / 多屏 / 摄像头"相关的行实时打出来。
  一边在车机上操作（打转向灯、挂挡、踩加速、开360影像），一边看这里的变化，
  最快能看出"哪个动作触发了哪条日志、信号是怎么走的"。

.EXAMPLE
  .\livewatch.ps1 -Connect 192.168.0.101:5555
  .\livewatch.ps1 -Serial 192.168.0.101:5555 -Pattern "turn|rpm|vehicle"
  # Ctrl+C 停止
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$Connect = "",
    [string]$Adb = "",
    [string]$Pattern = "vehicle|Vehicle|signal|Signal|turn|Turn|rpm|RPM|battery|Battery|power|Power|hud|HUD|avm|AVM|cluster|Cluster|gear|Gear|CarService|car_|steering|CAN"
)

$ErrorActionPreference = "Continue"

if (-not $Adb) { $Adb = $env:REA_ADB_PATH }
if (-not $Adb) {
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { $Adb = $c.Source }
}
if (-not $Adb) {
    $cand = "C:\Users\lushu\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
    if (Test-Path $cand) { $Adb = $cand }
}
if (-not $Adb -or -not (Test-Path $Adb)) { throw "找不到 adb" }

$target = $Serial
if ($Connect) {
    Write-Host "== adb connect $Connect" -ForegroundColor Cyan
    (& $Adb connect $Connect) 2>&1 | ForEach-Object { "   $_" }
    Start-Sleep -Seconds 3
    if (-not $target) { $target = $Connect }
}

$argList = @()
if ($target) { $argList += @("-s", $target) }
$argList += @("logcat", "-v", "time")

Write-Host ""
Write-Host "== 实时监视中（Ctrl+C 停止）" -ForegroundColor Green
Write-Host "== 过滤: $Pattern" -ForegroundColor DarkGray
Write-Host "== 现在去车机上操作：打转向灯 / 挂挡 / 踩加速 / 开360影像 …" -ForegroundColor Green
Write-Host ""

& $Adb @argList | Select-String -Pattern $Pattern
