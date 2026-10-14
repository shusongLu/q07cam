<#
.SYNOPSIS
  在车机上启动 / 停止「不安装版悬浮窗」（需要 adb root）。

.DESCRIPTION
  以 root 身份用 app_process 拉起 dex，悬浮在中控屏上实时显示
  功率 / 转速 / 车速 / 电量 / 续航 / 挡位 / 转向灯。
  不安装、不改 /system。车机重启后需要重新执行一次。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\tools\run-overlay.ps1 -Serial 192.168.137.167:5555
  powershell -ExecutionPolicy Bypass -File .\tools\run-overlay.ps1 -Serial 192.168.137.167:5555 -Stop
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$Adb = "",
    [switch]$Stop,
    [switch]$NoRoot
)

$ErrorActionPreference = "Continue"

if (-not $Adb) { $Adb = $env:REA_ADB_PATH }
if (-not $Adb) { $Adb = "adb" }

$pargs = @()
if ($Serial) { $pargs += @("-s", $Serial) }

function Invoke-Adb {
    param([string[]]$a)
    $all = $pargs + $a
    return (& $Adb @all 2>&1 | Out-String).Trim()
}

if ($Stop) {
    Write-Host "停止悬浮窗 …" -ForegroundColor Yellow
    $out = Invoke-Adb @("shell", "ps -A -o PID,ARGS | grep q07overlay.Main | grep -v grep")
    $procIds = [regex]::Matches($out, '(?m)^\s*(\d+)\s') | ForEach-Object { $_.Groups[1].Value }
    foreach ($procId in $procIds) {
        Invoke-Adb @("shell", "kill $procId") | Out-Null
        Write-Host "  killed PID $procId"
    }
    if (-not $procIds) { Write-Host "  (没有在跑)" }
    exit 0
}

if (-not $NoRoot) {
    Write-Host "adb root …" -ForegroundColor Cyan
    Invoke-Adb @("root") | Out-Null
    Start-Sleep -Seconds 4
    if ($Serial) { Invoke-Adb @("connect", $Serial) | Out-Null; Start-Sleep -Seconds 2 }
}

$id = Invoke-Adb @("shell", "id")
Write-Host "身份: $id"
if ($id -notmatch "uid=0") {
    Write-Host "⚠ 不是 root，悬浮窗可能无法显示（需要 root 才能加 TYPE_APPLICATION_OVERLAY）" -ForegroundColor Red
}

$dex = "/data/local/tmp/q07overlay.dex"
$exists = Invoke-Adb @("shell", "ls -l $dex")
if ($exists -match "No such") { throw "车上没有 $dex，先跑 tools\build-overlay.ps1 -Push" }

Write-Host "启动悬浮窗 …" -ForegroundColor Cyan
Invoke-Adb @("shell", "rm -f /data/local/tmp/q07overlay.log") | Out-Null
Invoke-Adb @("shell", "CLASSPATH=$dex app_process /system/bin q07overlay.Main > /data/local/tmp/q07overlay.log 2>&1 &") | Out-Null
Start-Sleep -Seconds 5

Write-Host "--- 日志 ---" -ForegroundColor DarkGray
Invoke-Adb @("shell", "cat /data/local/tmp/q07overlay.log")
Write-Host "`n已在中控屏左上角显示。停止: 本脚本加 -Stop" -ForegroundColor Green
