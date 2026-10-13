<#
.SYNOPSIS
  Q07 车机侦察。adbs 只读取证：车辆信号 / 多屏 / 摄像头 / HUD / 广播。
  产物直接可交给 REA 逆向。不安装任何 APK。

.EXAMPLE
  # 一键连上并全量取证（推荐）
  .\adbrecon.ps1 -Connect 192.168.0.101:5555 -TurnSignal -PullApks

  # 按动作分段抓日志（每次动作单独一个文件，便于 diff 出信号）
  .\adbrecon.ps1 -Actions "挂D挡","踩加速(功率上升)","踩刹车/能量回收","打开360影像"

  # 已有连接
  .\adbrecon.ps1 -Serial 192.168.0.101:5555
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$Connect = "",
    [string]$OutRoot = "",
    [string]$Adb = "",
    [int]$LogLines = 3000,
    [int]$LogSeconds = 15,
    [string[]]$Actions = @(),
    [switch]$TurnSignal,
    [switch]$PullApks,
    [int]$PullLimit = 10
)

$ErrorActionPreference = "Continue"
$script:Serial = $Serial
$script:Adb = $Adb

# ---------- 定位 adb ----------
if (-not $script:Adb) { $script:Adb = $env:REA_ADB_PATH }
if (-not $script:Adb) {
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { $script:Adb = $c.Source }
}
if (-not $script:Adb) {
    $cand = "C:\Users\lushu\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
    if (Test-Path $cand) { $script:Adb = $cand }
}
if (-not $script:Adb -or -not (Test-Path $script:Adb)) {
    throw "找不到 adb。可设置 REA_ADB_PATH 或 -Adb <路径>"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CmdArgs)
    $all = @()
    if ($script:Serial) { $all += @("-s", $script:Serial) }
    $all += $CmdArgs
    return (& $script:Adb @all 2>&1 | Out-String)
}

# ---------- 先连（可选）----------
if ($Connect) {
    Write-Host "== adb connect $Connect" -ForegroundColor Cyan
    (& $script:Adb connect $Connect) 2>&1 | ForEach-Object { "   $_" }
    Start-Sleep -Seconds 3
}

# ---------- 选择设备 ----------
if (-not $script:Serial) {
    $devOut = (& $script:Adb devices) 2>&1 | Out-String
    $dev = ($devOut -split "`r?`n" | Where-Object { $_ -match "^\S+\s+device$" } | Select-Object -First 1)
    if (-not $dev) { throw "没有已连接的设备。用 -Connect <IP:端口> 或先 adb connect。`n$devOut" }
    $script:Serial = ($dev -split "\s+")[0]
}
Write-Host "== 目标设备: $($script:Serial)" -ForegroundColor Cyan

# ---------- 输出目录 ----------
if (-not $OutRoot) { $OutRoot = Join-Path $PSScriptRoot "recon-out" }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $OutRoot $stamp
New-Item -ItemType Directory -Force -Path $out | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
Write-Host "== 输出目录: $out" -ForegroundColor Cyan

function Save-Dump {
    param([string]$Name, [string[]]$CmdArgs)
    $text = Invoke-Adb @CmdArgs
    [System.IO.File]::WriteAllText((Join-Path $out $Name), $text, $utf8)
    Write-Host ("   {0,-30} {1,6} 行" -f $Name, ($text -split "`n").Count)
    return $text
}

Write-Host "`n[1/6] 设备与系统属性" -ForegroundColor Yellow
$props = Save-Dump "01-getprop.txt" "shell" "getprop"
Save-Dump "02-build.txt" "shell" "getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.product.device; getprop ro.build.characteristics; getprop ro.build.fingerprint" | Out-Null
Save-Dump "03-env.txt" "shell" "getenforce; id; which su; ls -l /system/xbin/su /system/bin/su 2>/dev/null" | Out-Null
Save-Dump "04-features.txt" "shell" "pm list features" | Out-Null

Write-Host "`n[2/6] 服务 / 车辆 / 多屏 / 摄像头" -ForegroundColor Yellow
Save-Dump "10-services.txt" "shell" "service list" | Out-Null
Save-Dump "11-car_service.txt" "shell" "dumpsys car_service" | Out-Null
Save-Dump "12-activity_services.txt" "shell" "dumpsys activity services" | Out-Null
Save-Dump "13-display.txt" "shell" "dumpsys display" | Out-Null
Save-Dump "14-window_displays.txt" "shell" "dumpsys window displays" | Out-Null
Save-Dump "15-wm.txt" "shell" "wm size; wm density" | Out-Null
Save-Dump "16-media_camera.txt" "shell" "dumpsys media.camera" | Out-Null

# 关键：最近广播 + 已注册的接收者 → 常能直接看出车辆信号是怎么发的
$bc = Save-Dump "17-broadcasts.txt" "shell" "dumpsys activity broadcasts"
[System.IO.File]::WriteAllText(
    (Join-Path $out "17b-broadcasts_filtered.txt"),
    (($bc -split "`r?`n" | Where-Object { $_ -match "(?i)vehicle|car|signal|turn|rpm|battery|power|hud|avm|can|gear|speed" }) -join "`r`n"),
    $utf8)

Write-Host "`n[3/6] 应用清单与候选" -ForegroundColor Yellow
$pkgs = Save-Dump "20-packages.txt" "shell" "pm list packages -f"
Save-Dump "21-packages3.txt" "shell" "pm list packages -3 -f" | Out-Null

$keywords = @("vehicle","car","can","hud","avm","camera","cabin","cluster","display",
              "signal","energy","battery","bms","power","engine","tbox","cockpit",
              "qiyuan","changan","sda","psd","passenger","ecu","ivi","hmi")
$candNames = @()
foreach ($line in ($pkgs -split "`r?`n")) {
    if ($line -notmatch "^package:") { continue }
    $pkg = ($line -replace "^package:.*=", "").Trim()
    foreach ($k in $keywords) {
        if ($pkg.ToLower().Contains($k)) { $candNames += $pkg; break }
    }
}
$candNames = $candNames | Sort-Object -Unique
[System.IO.File]::WriteAllText((Join-Path $out "22-candidates.txt"), ($candNames -join "`r`n"), $utf8)
Write-Host ("   候选包 {0} 个 -> 22-candidates.txt" -f $candNames.Count)

# 候选包的权限 / 接收器动作（Receiver 动作名常常就是信号广播）
$detail = New-Object System.Text.StringBuilder
$recv = New-Object System.Text.StringBuilder
foreach ($p in $candNames) {
    [void]$detail.AppendLine("========== $p ==========")
    [void]$detail.AppendLine((Invoke-Adb "shell" "dumpsys package $p | grep -E 'versionName|versionCode|signature|sharedUserId|requested permissions|android.permission' | head -40"))
    [void]$detail.AppendLine("")
    $r = Invoke-Adb "shell" "dumpsys package $p | grep -iE 'Receiver Resolver|Service Resolver|Action:|Scheme:' | head -60"
    if ($r.Trim().Length -gt 0) {
        [void]$recv.AppendLine("========== $p ==========")
        [void]$recv.AppendLine($r)
        [void]$recv.AppendLine("")
    }
}
[System.IO.File]::WriteAllText((Join-Path $out "23-candidate_detail.txt"), $detail.ToString(), $utf8)
[System.IO.File]::WriteAllText((Join-Path $out "24-candidate_receivers.txt"), $recv.ToString(), $utf8)
Write-Host "   候选包权限/接收器 -> 23-*.txt / 24-*.txt"

Write-Host "`n[4/6] 日志快照" -ForegroundColor Yellow
Save-Dump "30-logcat.txt" "shell" "logcat -d -t $LogLines -v time" | Out-Null

# ---------- 按动作分段抓日志 ----------
$actionList = @()
if ($TurnSignal) {
    $actionList += @("打左转灯 约3秒，然后回正")
    $actionList += @("打右转灯 约3秒，然后回正")
}
$actionList += $Actions

if ($actionList.Count -gt 0) {
    Write-Host "`n[5/6] 按动作分段抓日志（共 $($actionList.Count) 个）" -ForegroundColor Yellow
    $i = 0
    foreach ($act in $actionList) {
        $i++
        $slug = ($act -replace "[^\w\u4e00-\u9fa5]+", "-").Trim("-")
        Write-Host ""
        Write-Host (">>> [{0}/{1}] 请准备：{2}" -f $i, $actionList.Count, $act) -ForegroundColor Green
        Write-Host "    5 秒后开始录 $LogSeconds 秒……" -ForegroundColor Green
        Start-Sleep -Seconds 5
        Invoke-Adb "logcat" "-c" | Out-Null
        $logPath = Join-Path $out ("5{0}-action-{1}.txt" -f $i, $slug)
        $argList = @()
        if ($script:Serial) { $argList += @("-s", $script:Serial) }
        $argList += @("logcat", "-v", "time")
        $proc = Start-Process -FilePath $script:Adb -ArgumentList $argList -RedirectStandardOutput $logPath -PassThru -NoNewWindow
        Start-Sleep -Seconds $LogSeconds
        try { $proc.Kill() } catch {}
        Write-Host ("    已保存: {0}" -f (Split-Path $logPath -Leaf)) -ForegroundColor Green
    }
} else {
    Write-Host "`n[5/6] 跳过动作抓取（加 -TurnSignal 或 -Actions 启用）" -ForegroundColor DarkGray
}

# ---------- 拉取 APK ----------
if ($PullApks) {
    Write-Host "`n[6/6] 拉取候选 APK 供 REA 分析" -ForegroundColor Yellow
    $apkDir = Join-Path $out "apks"
    New-Item -ItemType Directory -Force -Path $apkDir | Out-Null
    $n = 0
    foreach ($p in $candNames) {
        if ($n -ge $PullLimit) { break }
        $paths = (Invoke-Adb "shell" "pm path $p") -split "`r?`n" | Where-Object { $_ -match "^package:" }
        if (-not $paths) { continue }
        $dir = Join-Path $apkDir $p
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $k = 0
        foreach ($pp in $paths) {
            $dev = ($pp -replace "^package:", "").Trim()
            $name = if ($k -eq 0) { "base.apk" } else { "split$k.apk" }
            $all = @()
            if ($script:Serial) { $all += @("-s", $script:Serial) }
            $all += @("pull", $dev, (Join-Path $dir $name))
            & $script:Adb @all 2>&1 | Out-Null
            $k++
        }
        Write-Host ("   pulled {0}" -f $p)
        $n++
    }
    Write-Host ("   共拉取 {0} 个包 -> apks\" -f $n) -ForegroundColor Green
} else {
    Write-Host "`n[6/6] 跳过 APK 拉取（加 -PullApks 启用）" -ForegroundColor DarkGray
}

# ---------- 摘要 ----------
Write-Host "`n== 生成摘要" -ForegroundColor Yellow
$s = New-Object System.Text.StringBuilder
[void]$s.AppendLine("# Q07 车机侦察摘要")
[void]$s.AppendLine("")
[void]$s.AppendLine("- 设备: $($script:Serial)")
[void]$s.AppendLine("- 时间: $stamp")
[void]$s.AppendLine("")

$release = (Invoke-Adb "shell" "getprop ro.build.version.release").Trim()
$sdk = (Invoke-Adb "shell" "getprop ro.build.version.sdk").Trim()
$chars = (Invoke-Adb "shell" "getprop ro.build.characteristics").Trim()
$model = (Invoke-Adb "shell" "getprop ro.product.model").Trim()
[void]$s.AppendLine("## 系统")
[void]$s.AppendLine("- Android $release (API $sdk), model=$model")
[void]$s.AppendLine("- characteristics=$chars")
$isAaos = ($chars -match "automotive") -or ($props -match "car_service")
[void]$s.AppendLine("- 疑似 Android Automotive (AAOS): **$isAaos**")

$carSvc = Get-Content (Join-Path $out "11-car_service.txt") -Raw -ErrorAction SilentlyContinue
[void]$s.AppendLine("")
[void]$s.AppendLine("## 车辆服务")
if ($carSvc -and $carSvc.Trim().Length -gt 0 -and $carSvc -notmatch "Can't find service") {
    $propCount = ([regex]::Matches($carSvc, "id:\s*0x[0-9a-fA-F]+")).Count
    [void]$s.AppendLine("- dumpsys car_service **有内容**，属性条目约 $propCount → 优先看 11-car_service.txt")
} else {
    [void]$s.AppendLine("- 无 car_service（非 AAOS 或已裁剪）")
}

$svc = Get-Content (Join-Path $out "10-services.txt") -Raw -ErrorAction SilentlyContinue
[void]$s.AppendLine("")
[void]$s.AppendLine("## service list 命中")
foreach ($k in @("vehicle","car","hud","camera","avm","cluster","display","signal","can")) {
    $hit = ($svc -split "`r?`n" | Where-Object { $_ -match $k })
    if ($hit) {
        [void]$s.AppendLine("- [$k] " + (($hit | Select-Object -First 4 | ForEach-Object { $_.Trim() }) -join " | "))
    }
}

$bf = Get-Content (Join-Path $out "17b-broadcasts_filtered.txt") -Raw -ErrorAction SilentlyContinue
[void]$s.AppendLine("")
[void]$s.AppendLine("## 广播命中（17b-broadcasts_filtered.txt）")
if ($bf -and $bf.Trim().Length -gt 0) {
    foreach ($l in (($bf -split "`r?`n" | Select-Object -First 25))) { [void]$s.AppendLine("- $($l.Trim())") }
} else {
    [void]$s.AppendLine("- 无命中")
}

$disp = Get-Content (Join-Path $out "13-display.txt") -Raw -ErrorAction SilentlyContinue
[void]$s.AppendLine("")
[void]$s.AppendLine("## 屏幕")
$devs = ($disp -split "`r?`n" | Where-Object { $_ -match "DisplayDeviceInfo\{" })
foreach ($d in $devs) { [void]$s.AppendLine("- $($d.Trim())") }
if (-not $devs) { [void]$s.AppendLine("- (未解析到，见 13-display.txt / 14-window_displays.txt)") }

$root = (Invoke-Adb "shell" "which su").Trim()
$rootText = if ($root) { $root } else { "(空，未 root)" }
[void]$s.AppendLine("")
[void]$s.AppendLine("## root")
[void]$s.AppendLine("- which su => $rootText")

[void]$s.AppendLine("")
[void]$s.AppendLine("## 候选包（$($candNames.Count)）")
foreach ($p in ($candNames | Select-Object -First 40)) { [void]$s.AppendLine("- $p") }

[void]$s.AppendLine("")
[void]$s.AppendLine("## 下一步")
[void]$s.AppendLine("1. 读 11-car_service.txt / 17b-broadcasts_filtered.txt / 24-candidate_receivers.txt")
[void]$s.AppendLine("2. 对比 5x-action-*.txt，找出动作对应的信号行")
[void]$s.AppendLine("3. 把整个目录带回，交给 REA 分析 apks/")

[System.IO.File]::WriteAllText((Join-Path $out "SUMMARY.md"), $s.ToString(), $utf8)

Write-Host "`n== 完成 ==" -ForegroundColor Green
Write-Host "目录: $out"
Write-Host "先看: SUMMARY.md"
